// Copyright 2026 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.vfs.FileStatus;

/** The status of a file on Windows, as returned by {@link WindowsFileOperations#statIfFound}. */
final class WindowsFileStatus implements FileStatus {

  // The attribute bits this class reports on. See
  // https://learn.microsoft.com/en-us/windows/win32/fileio/file-attribute-constants.
  // Note that FILE_ATTRIBUTE_DIRECTORY is set on a junction and on a directory symlink too, so
  // telling links from directories must test FILE_ATTRIBUTE_REPARSE_POINT too.
  private static final int FILE_ATTRIBUTE_READONLY = 0x00000001;
  private static final int FILE_ATTRIBUTE_DIRECTORY = 0x00000010;
  private static final int FILE_ATTRIBUTE_DEVICE = 0x00000040;
  private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x00000400;

  private final int attributes;
  private final long size;
  private final long lastModifiedTimeNanos;
  private final long lastChangeTimeNanos;

  /** Times are in nanoseconds since the Unix epoch. */
  WindowsFileStatus(
      int attributes, long size, long lastModifiedTimeNanos, long lastChangeTimeNanos) {
    this.attributes = attributes;
    this.size = size;
    this.lastModifiedTimeNanos = lastModifiedTimeNanos;
    this.lastChangeTimeNanos = lastChangeTimeNanos;
  }

  @Override
  public boolean isFile() {
    return (attributes & (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DIRECTORY)) == 0
        || (attributes & FILE_ATTRIBUTE_DEVICE) != 0;
  }

  @Override
  public boolean isSpecialFile() {
    return (attributes & FILE_ATTRIBUTE_DEVICE) != 0;
  }

  @Override
  public boolean isDirectory() {
    return (attributes & (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DIRECTORY))
        == FILE_ATTRIBUTE_DIRECTORY;
  }

  @Override
  public boolean isSymbolicLink() {
    // Bazel treats both symlinks and junctions as symbolic links.
    return (attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
  }

  @Override
  public long getSize() {
    return size;
  }

  @Override
  public long getLastModifiedTimeNanos() {
    return lastModifiedTimeNanos;
  }

  @Override
  public long getLastChangeTimeNanos() {
    return lastChangeTimeNanos;
  }

  @Override
  public long getNodeId() {
    // TODO(bazel-team): Consider making use of attributes.fileKey().
    return -1;
  }

  @Override
  public int getPermissions() {
    // Files on Windows are implicitly readable and executable.
    return 0555 | ((attributes & FILE_ATTRIBUTE_READONLY) != 0 ? 0 : 0200);
  }
}
