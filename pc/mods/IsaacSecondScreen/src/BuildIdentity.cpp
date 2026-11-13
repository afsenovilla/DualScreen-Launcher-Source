#include "BuildIdentity.h"

#include <bcrypt.h>

#include <cstdio>
#include <cstring>
#include <vector>

namespace isaacds {
namespace {

// Both accepted files are the same J460 linker output (identical timestamp and
// identical .text/.rdata/.data layout). The Steam executable additionally
// carries the SteamStub `.bind` section; the DRM-free executable does not.
// Every RVA used by the native hooks is identical in both.
struct KnownBuild {
  uint64_t bytes;
  uint32_t image_size;
  const char* sha256;
  const char* label;
};
constexpr uint32_t kExpectedTimestamp = 0x69E6E3A7u;
constexpr KnownBuild kKnownBuilds[] = {
    {9362944ull, 0x0092C000u,
     "e5faf6f513c6de9cc9ff1c335ef9c541e2b33fc623aa3b8ffbd2e6a6c0027bb0",
     "Repentance+ v1.9.7.17.J460 (PE32 Steam build)"},
    {9176064ull, 0x008FE000u,
     "b4743967f3d31050f7b4222e42ac6beae6a0b7850b1f1adaac07ef7b94d01ec8",
     "Repentance+ v1.9.7.17.J460 (PE32 DRM-free build)"},
};

bool HashFile(const wchar_t* path, char output[65], uint64_t* file_size) {
  if (!path || !output || !file_size) return false;
  HANDLE file = CreateFileW(path, GENERIC_READ,
                            FILE_SHARE_READ | FILE_SHARE_WRITE |
                                FILE_SHARE_DELETE,
                            nullptr, OPEN_EXISTING, FILE_FLAG_SEQUENTIAL_SCAN,
                            nullptr);
  if (file == INVALID_HANDLE_VALUE) return false;
  LARGE_INTEGER size = {};
  if (!GetFileSizeEx(file, &size) || size.QuadPart < 0) {
    CloseHandle(file);
    return false;
  }
  *file_size = static_cast<uint64_t>(size.QuadPart);

  BCRYPT_ALG_HANDLE algorithm = nullptr;
  BCRYPT_HASH_HANDLE hash = nullptr;
  DWORD object_bytes = 0;
  DWORD result_bytes = 0;
  bool ok = BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM,
                                        nullptr, 0) == 0 &&
            BCryptGetProperty(algorithm, BCRYPT_OBJECT_LENGTH,
                              reinterpret_cast<PUCHAR>(&object_bytes),
                              sizeof(object_bytes), &result_bytes, 0) == 0;
  std::vector<unsigned char> object(object_bytes);
  if (ok) {
    ok = BCryptCreateHash(algorithm, &hash, object.data(), object_bytes,
                          nullptr, 0, 0) == 0;
  }
  std::vector<unsigned char> buffer(1024 * 1024);
  while (ok) {
    DWORD read = 0;
    if (!ReadFile(file, buffer.data(), static_cast<DWORD>(buffer.size()),
                  &read, nullptr)) {
      ok = false;
      break;
    }
    if (!read) break;
    ok = BCryptHashData(hash, buffer.data(), read, 0) == 0;
  }
  unsigned char digest[32] = {};
  if (ok) ok = BCryptFinishHash(hash, digest, sizeof(digest), 0) == 0;
  if (hash) BCryptDestroyHash(hash);
  if (algorithm) BCryptCloseAlgorithmProvider(algorithm, 0);
  CloseHandle(file);
  if (!ok) return false;
  for (size_t index = 0; index < sizeof(digest); ++index) {
    std::snprintf(output + index * 2, 3, "%02x", digest[index]);
  }
  output[64] = '\0';
  return true;
}

bool ReadPeIdentity(const wchar_t* path, BuildIdentity* result) {
  if (!path || !result) return false;
  HANDLE file = CreateFileW(path, GENERIC_READ,
                            FILE_SHARE_READ | FILE_SHARE_WRITE |
                                FILE_SHARE_DELETE,
                            nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL,
                            nullptr);
  if (file == INVALID_HANDLE_VALUE) return false;

  IMAGE_DOS_HEADER dos = {};
  DWORD read = 0;
  bool ok = ReadFile(file, &dos, sizeof(dos), &read, nullptr) &&
            read == sizeof(dos) && dos.e_magic == IMAGE_DOS_SIGNATURE &&
            dos.e_lfanew > 0;
  LARGE_INTEGER size = {};
  if (ok) ok = GetFileSizeEx(file, &size) && size.QuadPart >= 0;
  if (ok) {
    const uint64_t nt_offset = static_cast<uint64_t>(dos.e_lfanew);
    ok = nt_offset <= static_cast<uint64_t>(size.QuadPart) &&
         sizeof(IMAGE_NT_HEADERS32) <=
             static_cast<uint64_t>(size.QuadPart) - nt_offset;
  }

  IMAGE_NT_HEADERS32 nt = {};
  if (ok) {
    LARGE_INTEGER offset = {};
    offset.QuadPart = dos.e_lfanew;
    ok = SetFilePointerEx(file, offset, nullptr, FILE_BEGIN) &&
         ReadFile(file, &nt, sizeof(nt), &read, nullptr) &&
         read == sizeof(nt) && nt.Signature == IMAGE_NT_SIGNATURE &&
         nt.OptionalHeader.Magic == IMAGE_NT_OPTIONAL_HDR32_MAGIC;
  }
  CloseHandle(file);
  if (!ok) return false;

  result->machine = nt.FileHeader.Machine;
  result->timestamp = nt.FileHeader.TimeDateStamp;
  result->image_size = nt.OptionalHeader.SizeOfImage;
  return true;
}

}  // namespace

BuildIdentity IdentifyExecutablePath(const wchar_t* path) {
  BuildIdentity result;
  if (!ReadPeIdentity(path, &result)) {
    std::snprintf(result.label, sizeof(result.label), "invalid PE32 file");
    return result;
  }
  if (!HashFile(path, result.sha256, &result.file_size)) {
    std::snprintf(result.label, sizeof(result.label), "executable hash failed");
    return result;
  }
  const char* label = "unsupported Binding of Isaac executable";
  if (result.machine == IMAGE_FILE_MACHINE_I386 &&
      result.timestamp == kExpectedTimestamp) {
    for (const KnownBuild& build : kKnownBuilds) {
      if (result.image_size == build.image_size &&
          result.file_size == build.bytes &&
          std::strcmp(result.sha256, build.sha256) == 0) {
        result.supported = true;
        label = build.label;
        break;
      }
    }
  }
  std::snprintf(result.label, sizeof(result.label), "%s", label);
  return result;
}

BuildIdentity IdentifyExecutable(HMODULE image) {
  wchar_t path[MAX_PATH] = {};
  if (!image || !GetModuleFileNameW(image, path, MAX_PATH)) {
    BuildIdentity result;
    std::snprintf(result.label, sizeof(result.label), "unreadable executable");
    return result;
  }
  // The SHA-256 gate is over the installed file, so read every accompanying
  // PE field from that same immutable source rather than translated memory.
  return IdentifyExecutablePath(path);
}

}  // namespace isaacds
