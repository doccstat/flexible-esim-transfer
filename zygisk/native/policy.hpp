#pragma once
#include <string>
#include <string_view>

namespace fet {
inline std::string_view select(int uid, bool child, std::string_view process, std::string_view directory) {
    if (child || uid < 1000 || uid >= 100000 || (uid >= 90000)) return {};
    auto owns = [&](std::string_view package) {
        return directory == std::string("/data/user/0/") + std::string(package)
            || directory == std::string("/data/user_de/0/") + std::string(package)
            || directory == std::string("/data/data/") + std::string(package);
    };
    if (uid == 1001 && process == "com.android.phone" && owns("com.android.phone")) return "com.android.phone";
    if (uid < 10000) return {};
    if (process == "com.google.android.euicc" && owns(process)) return process;
    // Exact transfer hosts, never a package-prefix wildcard or the integrity worker.
    if ((process == "com.google.android.gms" || process == "com.google.android.gms.ui")
            && owns("com.google.android.gms")) return "com.google.android.gms";
    return {};
}
}
