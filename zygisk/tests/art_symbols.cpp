#include <cassert>
#include <dlfcn.h>
#include "art_symbols.hpp"

int main(int argc, char **argv) {
    assert(argc == 3);
    void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    assert(library);
    auto exported = reinterpret_cast<void *(*)()>(dlsym(library, "fet_exported_symbol"));
    assert(exported);
    {
        fet::ArtSymbols symbols;
        assert(symbols.find("fet_exported_symbol") == reinterpret_cast<void *>(exported));
        void *expected = argv[2][0] == '1' ? exported() : nullptr;
        assert(symbols.find("fet_private_symbol") == expected);
        assert(symbols.find("fet_private_", true) == expected);
        assert(symbols.find("fet_private_") == nullptr);
        assert(symbols.find("not_an_art_symbol", true) == nullptr);
    }
    dlclose(library);
}
