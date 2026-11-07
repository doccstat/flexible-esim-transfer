#include "policy.hpp"
#include <cassert>
int main() {
    using fet::select;
    assert(select(1001, false, "com.android.phone", "/data/user_de/0/com.android.phone") == "com.android.phone");
    assert(select(10175, false, "com.google.android.euicc", "/data/user/0/com.google.android.euicc") == "com.google.android.euicc");
    assert(select(10325, false, "com.google.android.gms", "/data/data/com.google.android.gms") == "com.google.android.gms");
    assert(select(10325, false, "com.google.android.gms.ui", "/data/user/0/com.google.android.gms") == "com.google.android.gms");
    for (auto name : {"com.google.android.gms.unstable", "com.google.android.gms.snet", "com.google.android.gms.persistent", "com.google.android.gms.evil", "system_server"})
        assert(select(10325, false, name, "/data/user/0/com.google.android.gms").empty());
    assert(select(1010325, false, "com.google.android.gms", "/data/user/10/com.google.android.gms").empty());
    assert(select(99000, false, "com.google.android.gms", "/data/user/0/com.google.android.gms").empty());
    assert(select(10325, true, "com.google.android.gms", "/data/user/0/com.google.android.gms").empty());
    assert(select(10325, false, "com.google.android.gms", "/data/user/0/wrong.package").empty());
    assert(select(10325, false, "com.android.phone", "/data/user/0/com.android.phone").empty());
    assert(select(1001, false, "com.google.android.gms", "/data/user/0/com.google.android.gms").empty());
}
