#include <windows.h>

#include <cstring>
#include <iostream>
#include <string>

#include "BuildIdentity.h"

int main(int argc, char** argv) {
  if (argc != 2) {
    std::cerr << "usage: BuildIdentityTests <isaac-ng.exe>\n";
    return 2;
  }
  const int count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, argv[1],
                                        -1, nullptr, 0);
  if (count <= 1) return 3;
  std::wstring path(static_cast<size_t>(count), L'\0');
  if (!MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, argv[1], -1,
                           path.data(), count)) {
    return 4;
  }

  const isaacds::BuildIdentity identity =
      isaacds::IdentifyExecutablePath(path.c_str());
  if (!identity.supported || identity.machine != IMAGE_FILE_MACHINE_I386 ||
      identity.timestamp != 0x69E6E3A7u ||
      !((identity.image_size == 0x0092C000u &&
         identity.file_size == 9362944ull &&
         std::strcmp(identity.sha256,
                     "e5faf6f513c6de9cc9ff1c335ef9c541e2b33fc623aa3b8ffbd2e6a6c0027bb0") == 0) ||
        (identity.image_size == 0x008FE000u &&
         identity.file_size == 9176064ull &&
         std::strcmp(identity.sha256,
                     "b4743967f3d31050f7b4222e42ac6beae6a0b7850b1f1adaac07ef7b94d01ec8") == 0))) {
    std::cerr << "exact-build identity rejected: " << identity.label << '\n';
    return 5;
  }
  std::cout << "BuildIdentityTests passed\n";
  return 0;
}
