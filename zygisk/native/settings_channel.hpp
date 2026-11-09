#pragma once
#include <array>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <optional>
#include <string>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>

namespace fet {
inline bool transfer(int fd, void *bytes, size_t length, bool write) {
    auto p = static_cast<char *>(bytes);
    while (length) {
        ssize_t count = write ? send(fd, p, length, MSG_NOSIGNAL) : recv(fd, p, length, 0);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        p += count; length -= count;
    }
    return true;
}

inline bool channel_timeout(int fd) {
    timeval timeout{2, 0};
    return setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0
        && setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) == 0;
}

inline bool send_channel(int transport, int channel) {
    char version = 1;
    iovec payload{&version, 1};
    alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))]{};
    msghdr message{};
    message.msg_iov = &payload; message.msg_iovlen = 1;
    message.msg_control = control; message.msg_controllen = sizeof(control);
    auto cmsg = CMSG_FIRSTHDR(&message);
    cmsg->cmsg_level = SOL_SOCKET; cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    std::memcpy(CMSG_DATA(cmsg), &channel, sizeof(channel));
    ssize_t sent;
    do { sent = sendmsg(transport, &message, MSG_NOSIGNAL); } while (sent < 0 && errno == EINTR);
    return sent == 1;
}

inline int receive_channel(int transport) {
    char version = 0;
    iovec payload{&version, 1};
    alignas(cmsghdr) char control[CMSG_SPACE(2 * sizeof(int))]{};
    msghdr message{};
    message.msg_iov = &payload; message.msg_iovlen = 1;
    message.msg_control = control; message.msg_controllen = sizeof(control);
    ssize_t received;
    do { received = recvmsg(transport, &message, MSG_CMSG_CLOEXEC); } while (received < 0 && errno == EINTR);
    if (received < 0) return -1;
    int channel = -1;
    bool valid = received == 1 && version == 1 && !(message.msg_flags & (MSG_CTRUNC | MSG_TRUNC));
    for (auto cmsg = CMSG_FIRSTHDR(&message); cmsg; cmsg = CMSG_NXTHDR(&message, cmsg)) {
        if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS
                || cmsg->cmsg_len < CMSG_LEN(sizeof(int))) { valid = false; continue; }
        size_t bytes = cmsg->cmsg_len - CMSG_LEN(0);
        if (bytes % sizeof(int)) valid = false;
        for (size_t offset = 0; offset + sizeof(int) <= bytes; offset += sizeof(int)) {
            int fd;
            std::memcpy(&fd, CMSG_DATA(cmsg) + offset, sizeof(fd));
            if (channel < 0) channel = fd;
            else { close(fd); valid = false; }
        }
    }
    int type = 0; socklen_t length = sizeof(type);
    sockaddr_storage address{}; socklen_t address_length = sizeof(address);
    valid = valid && channel >= 0 && getsockopt(channel, SOL_SOCKET, SO_TYPE, &type, &length) == 0
            && type == SOCK_STREAM && getsockname(channel, reinterpret_cast<sockaddr *>(&address), &address_length) == 0
            && address.ss_family == AF_UNIX;
    if (!valid) { if (channel >= 0) close(channel); return -1; }
    return channel;
}

inline std::string read_config(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) return {};
    struct stat st{};
    std::array<char, 129> bytes{};
    ssize_t n = -1;
    if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_uid == 0 && (st.st_mode & 077) == 0) {
        do { n = read(fd, bytes.data(), bytes.size()); } while (n < 0 && errno == EINTR);
    }
    close(fd);
    return n > 0 && n <= 128 ? std::string(bytes.data(), n) : std::string{};
}

template <class ReadConfig> void serve_settings(int transport, ReadConfig read) {
    // The Zygisk socket belongs to zygote; use a companion-created pair after specialization.
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, pair) != 0) return;
    // Only the companion may configure its socket; SCM_RIGHTS preserves these timeouts.
    bool sent = channel_timeout(pair[0]) && send_channel(transport, pair[0]);
    close(pair[0]);
    if (sent) {
        for (char command; transfer(pair[1], &command, 1, false) && command == 'R';) {
            std::string text = read();
            uint8_t length = text.size() <= 128 ? text.size() : 0;
            if (!transfer(pair[1], &length, 1, true) || !transfer(pair[1], text.data(), length, true)) break;
        }
    }
    close(pair[1]);
}

inline std::optional<std::string> read_settings(int &fd) {
    char command = 'R'; uint8_t length = 0;
    std::array<char, 128> bytes{};
    if (fd >= 0 && transfer(fd, &command, 1, true)
            && transfer(fd, &length, 1, false) && length && length <= bytes.size()
            && transfer(fd, bytes.data(), length, false)) {
        bool valid = true;
        for (size_t i = 0; i < length; i++)
            if (bytes[i] != '\n' && (bytes[i] < '0' || bytes[i] > 'z')) valid = false;
        if (valid) return std::string(bytes.data(), length);
    }
    // Never reuse a truncated response, stale snapshot or failed channel for a later operation.
    if (fd >= 0) close(fd);
    fd = -1;
    return std::nullopt;
}
}
