extern "C" {
__attribute__((visibility("hidden"))) int fet_private_symbol() { return 42; }
__attribute__((visibility("default"))) void *fet_exported_symbol() {
    return reinterpret_cast<void *>(fet_private_symbol);
}
}
