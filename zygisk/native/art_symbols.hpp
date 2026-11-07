#pragma once
#include <elf.h>
#include <link.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstring>
#include <string>
#include <string_view>

namespace fet {
class ArtSymbols {
    void *mapping = MAP_FAILED;
    size_t length = 0;
    uintptr_t base = 0;
    template<class T> const T *at(size_t offset, size_t count = 1) const {
        if (offset > length || count > (length - offset) / sizeof(T)) return nullptr;
        return reinterpret_cast<const T *>(static_cast<const char *>(mapping) + offset);
    }
public:
    ArtSymbols() {
        std::string path;
        struct Search { std::string *path; uintptr_t *base; } search{&path, &base};
        dl_iterate_phdr([](dl_phdr_info *info, size_t, void *raw) {
            auto &s = *static_cast<Search *>(raw);
            std::string_view name(info->dlpi_name ? info->dlpi_name : "");
            if (!name.ends_with("/libart.so")) return 0;
            *s.path = name; *s.base = info->dlpi_addr; return 1;
        }, &search);
        int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        struct stat st{};
        if (fd < 0) return;
        if (fstat(fd, &st) == 0 && st.st_size > 0 && st.st_size <= 128 * 1024 * 1024) {
            length = st.st_size;
            mapping = mmap(nullptr, length, PROT_READ, MAP_PRIVATE, fd, 0);
        }
        close(fd);
    }
    ~ArtSymbols() { if (mapping != MAP_FAILED) munmap(mapping, length); }
    void *find(std::string_view name, bool prefix = false) const {
        if (mapping == MAP_FAILED) return nullptr;
        auto header = at<ElfW(Ehdr)>(0);
        if (!header || memcmp(header->e_ident, ELFMAG, SELFMAG) || header->e_shentsize != sizeof(ElfW(Shdr))) return nullptr;
        auto sections = at<ElfW(Shdr)>(header->e_shoff, header->e_shnum);
        if (!sections) return nullptr;
        for (size_t i = 0; i < header->e_shnum; i++) {
            const auto &section = sections[i];
            if (section.sh_type != SHT_SYMTAB && section.sh_type != SHT_DYNSYM) continue;
            if (section.sh_link >= header->e_shnum || section.sh_entsize != sizeof(ElfW(Sym))) continue;
            const auto &strings = sections[section.sh_link];
            auto names = at<char>(strings.sh_offset, strings.sh_size);
            size_t count = section.sh_size / sizeof(ElfW(Sym));
            auto symbols = at<ElfW(Sym)>(section.sh_offset, count);
            if (!names || !symbols) continue;
            for (size_t n = 0; n < count; n++) {
                auto &symbol = symbols[n];
                if (symbol.st_shndx == SHN_UNDEF || symbol.st_name >= strings.sh_size) continue;
                const char *start = names + symbol.st_name;
                auto end = static_cast<const char *>(memchr(start, 0, strings.sh_size - symbol.st_name));
                if (!end) continue;
                std::string_view candidate(start, end - start);
                if ((prefix && candidate.starts_with(name)) || (!prefix && candidate == name))
                    return reinterpret_cast<void *>(base + symbol.st_value);
            }
        }
        return nullptr;
    }
};
}
