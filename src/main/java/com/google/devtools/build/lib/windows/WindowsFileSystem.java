// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.windows;

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SymlinkTargetType;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import javax.annotation.Nullable;

/** File system implementation for Windows. */
@ThreadSafe
public class WindowsFileSystem extends JavaIoFileSystem {

  public static final LinkOption[] NO_OPTIONS = new LinkOption[0];
  public static final LinkOption[] NO_FOLLOW = new LinkOption[] {LinkOption.NOFOLLOW_LINKS};

  private final boolean createSymbolicLinks;

  public WindowsFileSystem(DigestHashFunction hashFunction, boolean createSymbolicLinks) {
    super(hashFunction);
    this.createSymbolicLinks = createSymbolicLinks;
  }

  @Override
  public String getFileSystemType(PathFragment path) {
    // TODO(laszlocsomor): implement this properly, i.e. actually query this information from
    // somewhere (java.nio.Filesystem? System.getProperty? implement JNI method and use WinAPI?).
    return "ntfs";
  }

  @Override
  public boolean delete(PathFragment path) throws IOException {
    Path nioPath;
    try {
      nioPath = getNioPath(path);
    } catch (InvalidPathException e) {
      return false;
    }

    long startTime = Profiler.instance().nanoTimeMaybe();
    try {
      return WindowsFileOperations.deletePath(nioPath.toString());
    } catch (java.nio.file.DirectoryNotEmptyException e) {
      throw new IOException(path.getPathString() + ERR_DIRECTORY_NOT_EMPTY, e);
    } catch (java.nio.file.AccessDeniedException e) {
      throw new IOException(path.getPathString() + ERR_PERMISSION_DENIED, e);
    } finally {
      Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_DELETE, path.getPathString());
    }
  }

  @Override
  public void createSymbolicLink(
      PathFragment linkPath, PathFragment targetFragment, SymlinkTargetType type)
      throws IOException {
    PathFragment targetPath =
        targetFragment.isAbsolute()
            ? targetFragment
            : (linkPath.getParentDirectory() != null
                ? linkPath.getParentDirectory().getRelative(targetFragment)
                : targetFragment);

    FileStatus stat = statIfFound(targetPath, /* followSymlinks= */ true);
    boolean existingFile = stat != null && stat.isFile();
    boolean existingDirectory = stat != null && stat.isDirectory();

    try {
      Path link = getNioPath(linkPath);
      Path target = getNioPath(targetPath);

      if (!createSymbolicLinks && existingFile) {
        // If symlinks aren't enabled and the target is an existing file, fall back to a copy.
        Files.copy(target, link);
      } else if (createSymbolicLinks
          && (existingFile || (!existingDirectory && type != SymlinkTargetType.DIRECTORY))) {
        // If symlinks are enabled and the target is not an existing or future directory, create a
        // symlink.
        WindowsFileOperations.createSymlink(link.toString(), target.toString());
      } else {
        // Otherwise, create a junction.
        WindowsFileOperations.createJunction(link.toString(), target.toString());
      }
    } catch (IOException e) {
      throw translateNioToIoException(linkPath, e);
    } catch (InvalidPathException e) {
      throw new IOException(linkPath.getPathString() + ERR_NO_SUCH_FILE_OR_DIR, e);
    }
  }

  @Override
  public PathFragment readSymbolicLink(PathFragment path) throws IOException {
    try {
      Path nioPath = getNioPath(path);
      return PathFragment.create(
          StringEncoding.platformToInternal(
              WindowsFileOperations.readSymlinkOrJunction(nioPath.toString())));
    } catch (InvalidPathException e) {
      FileNotFoundException fnfe = new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
      fnfe.initCause(e);
      throw fnfe;
    }
  }

  @Override
  public boolean supportsSymbolicLinksNatively(PathFragment path) {
    return createSymbolicLinks;
  }

  @Override
  public boolean mayBeCaseOrNormalizationInsensitive() {
    return true;
  }

  public static LinkOption[] symlinkOpts(boolean followSymlinks) {
    return followSymlinks ? NO_OPTIONS : NO_FOLLOW;
  }

  @Override
  public FileStatus stat(PathFragment path, boolean followSymlinks) throws IOException {
    final WindowsFileStatus status;
    try {
      status =
          WindowsFileOperations.statIfFound(
              getNioPath(path).toAbsolutePath().toString(), followSymlinks);
    } catch (InvalidPathException e) {
      FileNotFoundException fnfe = new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
      fnfe.initCause(e);
      throw fnfe;
    }
    if (status == null) {
      throw new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
    }
    return status;
  }

  @Override
  @Nullable
  public FileStatus statIfFound(PathFragment path, boolean followSymlinks) throws IOException {
    try {
      return WindowsFileOperations.statIfFound(
          getNioPath(path).toAbsolutePath().toString(), followSymlinks);
    } catch (InvalidPathException e) {
      // Same as WindowsFileSystem#stat: null where #stat throws FileNotFoundException
      return null;
    }
  }

  @Override
  public void setReadable(PathFragment path, boolean readable) {
    // Windows does not have a notion of readable files.
    // https://github.com/openjdk/jdk/blob/e52a2aeeacaeb26c801b6e31f8e67e61b1ea2de3/src/java.base/windows/native/libjava/WinNTFileSystem_md.c#L473-L476
  }

  @Override
  public void setExecutable(PathFragment path, boolean executable) {
    // Windows does not have a notion of executable files.
    // https://github.com/openjdk/jdk/blob/e52a2aeeacaeb26c801b6e31f8e67e61b1ea2de3/src/java.base/windows/native/libjava/WinNTFileSystem_md.c#L473-L476
  }

  @Override
  public void setWritable(PathFragment path, boolean writable) throws IOException {
    // Windows does not have a notion of read-only directories.
    // See https://learn.microsoft.com/en-us/windows/win32/fileio/file-attribute-constants.
    // JavaIoFileSystem#setWritable(dir, true) would throw, so reimplement it here as a no-op.
    if (isDirectory(path, /* followSymlinks= */ true)) {
      return;
    }
    super.setWritable(path, writable);
  }
}
