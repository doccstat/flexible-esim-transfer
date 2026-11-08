#pragma once
#include <elf.h>
#include <link.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstring>
#include <limits>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>
#include "xz.h"

namespace fet {
class ElfView {
    std::span<const uint8_t> image;
    template<class T> std::optional<T> read(size_t offset) const {
        if (offset > image.size() || sizeof(T) > image.size() - offset) return {};
        T value;
        // MiniDebugInfo may pack tables at unaligned file offsets.
        memcpy(&value, image.data() + offset, sizeof(T));
        return value;
    }
    std::span<const uint8_t> data(size_t offset, size_t size) const {
        return offset <= image.size() && size <= image.size() - offset ? image.subspan(offset, size)
                : std::span<const uint8_t>{};
    }
    std::optional<ElfW(Ehdr)> header() const {
        auto h = read<ElfW(Ehdr)>(0);
        if (!h || memcmp(h->e_ident, ELFMAG, SELFMAG)
                || h->e_ident[EI_CLASS] != (sizeof(void *) == 8 ? ELFCLASS64 : ELFCLASS32)
                || h->e_ident[EI_DATA] != ELFDATA2LSB || h->e_ident[EI_VERSION] != EV_CURRENT
                || h->e_version != EV_CURRENT || h->e_type != ET_DYN
                || h->e_ehsize != sizeof(*h) || h->e_shentsize != sizeof(ElfW(Shdr))
                || h->e_shoff > image.size()
                || h->e_shnum > (image.size() - h->e_shoff) / sizeof(ElfW(Shdr))) return {};
        return h;
    }
    ElfW(Shdr) section_at(const ElfW(Ehdr) &h, size_t index) const {
        return *read<ElfW(Shdr)>(h.e_shoff + index * sizeof(ElfW(Shdr)));
    }
    std::string_view string(const ElfW(Shdr) &table, size_t offset) const {
        if (table.sh_type != SHT_STRTAB || offset >= table.sh_size) return {};
        auto names = data(table.sh_offset, table.sh_size);
        if (names.empty()) return {};
        const char *start = reinterpret_cast<const char *>(names.data()) + offset;
        auto end = static_cast<const char *>(memchr(start, 0, table.sh_size - offset));
        return end ? std::string_view(start, end - start) : std::string_view{};
    }
public:
    explicit ElfView(std::span<const uint8_t> bytes) : image(bytes) {}
    uint16_t machine() const { auto h = header(); return h ? h->e_machine : 0; }
    std::span<const uint8_t> section(std::string_view name) const {
        auto h = header();
        if (!h || h->e_shstrndx >= h->e_shnum) return {};
        auto names = section_at(*h, h->e_shstrndx);
        for (size_t i = 0; i < h->e_shnum; i++) {
            auto s = section_at(*h, i);
            if (s.sh_type == SHT_PROGBITS && string(names, s.sh_name) == name) return data(s.sh_offset, s.sh_size);
        }
        return {};
    }
    std::optional<ElfW(Sym)> find(std::string_view name, bool prefix = false) const {
        auto h = header();
        if (name.empty() || !h) return {};
        for (size_t i = 0; i < h->e_shnum; i++) {
            auto s = section_at(*h, i);
            if (s.sh_type != SHT_SYMTAB && s.sh_type != SHT_DYNSYM) continue;
            if (s.sh_link >= h->e_shnum || s.sh_entsize != sizeof(ElfW(Sym))
                    || s.sh_size % sizeof(ElfW(Sym)) || data(s.sh_offset, s.sh_size).empty()) continue;
            auto names = section_at(*h, s.sh_link);
            size_t count = s.sh_size / sizeof(ElfW(Sym));
            for (size_t n = 0; n < count; n++) {
                auto symbol = *read<ElfW(Sym)>(s.sh_offset + n * sizeof(ElfW(Sym)));
                if (symbol.st_shndx == SHN_UNDEF
                        || (symbol.st_shndx >= h->e_shnum && symbol.st_shndx != SHN_ABS)) continue;
                auto candidate = string(names, symbol.st_name);
                if ((prefix && candidate.starts_with(name)) || (!prefix && candidate == name)) return symbol;
            }
        }
        return {};
    }
};

inline std::vector<uint8_t> mini_debug_info(std::span<const uint8_t> compressed) {
    constexpr size_t limit = 32 * 1024 * 1024;
    if (compressed.empty() || compressed.size() > limit) return {};
    static const bool initialized = [] { xz_crc32_init(); xz_crc64_init(); return true; }();
    (void) initialized;
    std::unique_ptr<xz_dec, decltype(&xz_dec_end)> decoder(xz_dec_init(XZ_DYNALLOC, limit), xz_dec_end);
    if (!decoder) return {};
    try {
        std::vector<uint8_t> output(64 * 1024);
        xz_buf buffer{};
        buffer.in = compressed.data(); buffer.in_size = compressed.size();
        for (;;) {
            buffer.out = output.data(); buffer.out_size = output.size();
            auto result = xz_dec_run(decoder.get(), &buffer);
            if (result == XZ_STREAM_END) {
                if (buffer.in_pos != buffer.in_size) return {};
                output.resize(buffer.out_pos);
                return output;
            }
            if (result != XZ_OK || buffer.out_pos != buffer.out_size || output.size() == limit) return {};
            output.resize(output.size() * 2);
        }
    } catch (const std::bad_alloc &) { return {}; }
}

class ArtSymbols {
    void *mapping = MAP_FAILED;
    size_t length = 0;
    uintptr_t base = 0;
    std::vector<uint8_t> debug;
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
        if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0 && st.st_size <= 128 * 1024 * 1024) {
            length = st.st_size;
            mapping = mmap(nullptr, length, PROT_READ, MAP_PRIVATE, fd, 0);
        }
        close(fd);
        if (mapping != MAP_FAILED) {
            ElfView elf({static_cast<const uint8_t *>(mapping), length});
            // Release ART libraries keep required private symbols in compressed MiniDebugInfo.
            debug = mini_debug_info(elf.section(".gnu_debugdata"));
            if (ElfView(debug).machine() != elf.machine()) debug.clear();
        }
    }
    ArtSymbols(const ArtSymbols &) = delete;
    ArtSymbols &operator=(const ArtSymbols &) = delete;
    ~ArtSymbols() { if (mapping != MAP_FAILED) munmap(mapping, length); }
    void *find(std::string_view name, bool prefix = false) const {
        if (mapping == MAP_FAILED) return nullptr;
        auto symbol = ElfView({static_cast<const uint8_t *>(mapping), length}).find(name, prefix);
        if (!symbol) symbol = ElfView(debug).find(name, prefix);
        if (!symbol) return nullptr;
        uintptr_t bias = symbol->st_shndx == SHN_ABS ? 0 : base;
        if (symbol->st_value > std::numeric_limits<uintptr_t>::max() - bias) return nullptr;
        return reinterpret_cast<void *>(bias + symbol->st_value);
    }
};
}
