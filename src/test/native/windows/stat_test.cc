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

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include "src/main/native/windows/stat.h"

#include <windows.h>

#include <cstdint>
#include <string>

#include "gtest/gtest.h"
#include "src/main/native/windows/file.h"
#include "src/main/native/windows/util.h"
#include "src/test/cpp/util/windows_test_util.h"

#if !defined(_WIN32) && !defined(__CYGWIN__)
#error("This test should only be run on Windows")
#endif  // !defined(_WIN32) && !defined(__CYGWIN__)

namespace bazel {
namespace windows {

using blaze_util::CreateDummyFile;
using blaze_util::DeleteAllUnder;
using blaze_util::GetTestTmpDirW;
using std::string;

static const std::wstring kUncPrefix = std::wstring(L"\\\\?\\");
static const string kContent = "hello";

static int64_t ToTicks(const FILETIME& time) {
  return static_cast<int64_t>(
      (static_cast<uint64_t>(time.dwHighDateTime) << 32) | time.dwLowDateTime);
}

class WindowsStatTest : public ::testing::Test {
 public:
  void TearDown() override { DeleteAllUnder(GetTestTmpDirW()); }

 protected:
  static std::wstring TmpDir() { return kUncPrefix + GetTestTmpDirW(); }
};

TEST_F(WindowsStatTest, TestReportsPathThatIsNotAbsoluteAndNormalized) {
  StatInfo info;
  for (const wchar_t* path : {L"", L"foo\\bar", L"c:\\foo\\..\\bar"}) {
    std::wstring error;
    EXPECT_EQ(StatResult::kError, Stat(path, false, info, error))
        << "path=" << path;
    EXPECT_FALSE(error.empty()) << "path=" << path;
  }
}

TEST_F(WindowsStatTest, TestReportsErrorForInvalidName) {
  std::wstring path(TmpDir() + L"\\in<valid");
  StatInfo info;
  std::wstring error;
  EXPECT_EQ(StatResult::kError, Stat(path, false, info, error));
  EXPECT_FALSE(error.empty());
}

TEST_F(WindowsStatTest, TestReportsMissingPath) {
  std::wstring path(TmpDir() + L"\\no_such_entry");
  StatInfo info;
  std::wstring error;
  EXPECT_EQ(StatResult::kDoesNotExist,
            Stat(path.c_str(), false, info, error));
  EXPECT_EQ(StatResult::kDoesNotExist,
            Stat(path.c_str(), true, info, error));
}

TEST_F(WindowsStatTest, TestDescribesRegularFile) {
  std::wstring path(TmpDir() + L"\\a_file");
  ASSERT_TRUE(CreateDummyFile(path, kContent));

  for (bool follow : {false, true}) {
    StatInfo info;
    std::wstring error;
    ASSERT_EQ(StatResult::kSuccess,
              Stat(path.c_str(), follow, info, error))
        << "follow=" << follow;
    EXPECT_EQ(0u, info.attributes & FILE_ATTRIBUTE_DIRECTORY);
    EXPECT_EQ(0u, info.attributes & FILE_ATTRIBUTE_REPARSE_POINT);
    EXPECT_EQ(static_cast<int64_t>(kContent.size()), info.size);
    EXPECT_GT(info.last_write_time.QuadPart, 0);
    EXPECT_GT(info.change_time.QuadPart, 0);
  }
}

TEST_F(WindowsStatTest, TestDescribesDirectory) {
  std::wstring path(TmpDir() + L"\\a_dir");
  ASSERT_TRUE(::CreateDirectoryW(path.c_str(), nullptr));

  StatInfo info;
  std::wstring error;
  ASSERT_EQ(StatResult::kSuccess, Stat(path.c_str(), false, info, error));
  EXPECT_NE(0u, info.attributes & FILE_ATTRIBUTE_DIRECTORY);
  EXPECT_EQ(0u, info.attributes & FILE_ATTRIBUTE_REPARSE_POINT);
}

TEST_F(WindowsStatTest, TestDescribesRootDirectory) {
  std::wstring root(TmpDir().substr(0, kUncPrefix.size() + 3));
  ASSERT_EQ(L'\\', root[root.size() - 1]);

  StatInfo info;
  std::wstring error;
  ASSERT_EQ(StatResult::kSuccess, Stat(root.c_str(), false, info, error));
  EXPECT_NE(0u, info.attributes & FILE_ATTRIBUTE_DIRECTORY);
}

TEST_F(WindowsStatTest, TestDescribesJunctionAndFollowsIt) {
  std::wstring target(TmpDir() + L"\\junc_target");
  std::wstring name(TmpDir() + L"\\junc");
  ASSERT_TRUE(::CreateDirectoryW(target.c_str(), nullptr));
  ASSERT_EQ(CreateJunctionResult::kSuccess,
            CreateJunction(name, target, nullptr));

  // NOFOLLOW describes the junction itself.
  StatInfo info;
  std::wstring error;
  ASSERT_EQ(StatResult::kSuccess, Stat(name.c_str(), false, info, error));
  EXPECT_NE(0u, info.attributes & FILE_ATTRIBUTE_REPARSE_POINT);
  EXPECT_NE(0u, info.attributes & FILE_ATTRIBUTE_DIRECTORY);

  // FOLLOW describes the target.
  ASSERT_EQ(StatResult::kSuccess, Stat(name.c_str(), true, info, error));
  EXPECT_EQ(0u, info.attributes & FILE_ATTRIBUTE_REPARSE_POINT);
  EXPECT_NE(0u, info.attributes & FILE_ATTRIBUTE_DIRECTORY);
}

TEST_F(WindowsStatTest, TestDescribesDanglingJunctionUnderNofollow) {
  std::wstring target(TmpDir() + L"\\gone");
  std::wstring name(TmpDir() + L"\\dangling_junc");
  ASSERT_TRUE(::CreateDirectoryW(target.c_str(), nullptr));
  ASSERT_EQ(CreateJunctionResult::kSuccess,
            CreateJunction(name, target, nullptr));
  ASSERT_TRUE(::RemoveDirectoryW(target.c_str()));

  StatInfo info;
  std::wstring error;
  ASSERT_EQ(StatResult::kSuccess, Stat(name.c_str(), false, info, error));
  EXPECT_NE(0u, info.attributes & FILE_ATTRIBUTE_REPARSE_POINT);
}

TEST_F(WindowsStatTest, TestDescribesFileReachedThroughJunction) {
  // Junctions in the middle of the path are always followed.
  std::wstring target(TmpDir() + L"\\via_target");
  std::wstring name(TmpDir() + L"\\via_junc");
  ASSERT_TRUE(::CreateDirectoryW(target.c_str(), nullptr));
  ASSERT_TRUE(CreateDummyFile(target + L"\\a_file", kContent));
  ASSERT_EQ(CreateJunctionResult::kSuccess,
            CreateJunction(name, target, nullptr));

  std::wstring path(name + L"\\a_file");
  for (bool follow : {false, true}) {
    StatInfo info;
    std::wstring error;
    ASSERT_EQ(StatResult::kSuccess,
              Stat(path.c_str(), follow, info, error))
        << "follow=" << follow;
    EXPECT_EQ(0u, info.attributes & FILE_ATTRIBUTE_REPARSE_POINT);
    EXPECT_EQ(0u, info.attributes & FILE_ATTRIBUTE_DIRECTORY);
    EXPECT_EQ(static_cast<int64_t>(kContent.size()), info.size);
  }
}

TEST_F(WindowsStatTest, TestDescribesFileAnotherHandleHoldsExclusively) {
  // GetFileInformationByName opens no handle, so sharing mode doesn't matter.
  if (::GetEnvironmentVariableW(kStatByHandleEnvVar, nullptr, 0) != 0 ||
      ::GetProcAddress(::GetModuleHandleW(L"kernel32.dll"),
                       "GetFileInformationByName") == nullptr) {
    GTEST_SKIP() << "Stat doesn't use GetFileInformationByName here";
  }
  std::wstring path(TmpDir() + L"\\exclusive");
  ASSERT_TRUE(CreateDummyFile(path, kContent));

  AutoHandle handle(::CreateFileW(path.c_str(), GENERIC_READ,
                                  /* dwShareMode */ 0, nullptr, OPEN_EXISTING,
                                  FILE_ATTRIBUTE_NORMAL, nullptr));
  ASSERT_TRUE(handle.IsValid());

  StatInfo info;
  std::wstring error;
  ASSERT_EQ(StatResult::kSuccess, Stat(path.c_str(), false, info, error));
  EXPECT_EQ(static_cast<int64_t>(kContent.size()), info.size);
}

TEST_F(WindowsStatTest, TestReadsTheSameFieldsAsTheCallsItReplaces) {
  std::wstring path(TmpDir() + L"\\a_file");
  ASSERT_TRUE(CreateDummyFile(path, kContent));

  StatInfo info;
  std::wstring error;
  ASSERT_EQ(StatResult::kSuccess, Stat(path.c_str(), false, info, error));

  WIN32_FILE_ATTRIBUTE_DATA expected = {};
  ASSERT_TRUE(
      ::GetFileAttributesExW(path.c_str(), GetFileExInfoStandard, &expected));
  FILE_BASIC_INFO expected_basic = {};
  {
    AutoHandle handle(::CreateFileW(
        path.c_str(), 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        nullptr, OPEN_EXISTING,
        FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, nullptr));
    ASSERT_TRUE(handle.IsValid());
    ASSERT_TRUE(::GetFileInformationByHandleEx(
        handle, FileBasicInfo, &expected_basic, sizeof(expected_basic)));
  }

  // Checks that the local FILE_STAT_BASIC_INFORMATION layout matches the SDK's.
  EXPECT_EQ(expected.dwFileAttributes, info.attributes);
  EXPECT_EQ((static_cast<int64_t>(expected.nFileSizeHigh) << 32) |
                expected.nFileSizeLow,
            info.size);
  EXPECT_EQ(ToTicks(expected.ftLastWriteTime),
            info.last_write_time.QuadPart);
  EXPECT_EQ(expected_basic.ChangeTime.QuadPart, info.change_time.QuadPart);
}

}  // namespace windows
}  // namespace bazel
