#include "settings_channel.hpp"
#include <cassert>
#include <dirent.h>
#include <thread>
#include <vector>
#include <sys/wait.h>

int descriptors() {
    DIR *directory = opendir("/proc/self/fd");
    assert(directory);
    int count = 0;
    while (readdir(directory)) count++;
    closedir(directory);
    return count;
}

void reject_message(char version, int count) {
    int transport[2], pair[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, transport) == 0);
    assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, pair) == 0);
    std::vector<int> descriptors(count, pair[0]);
    iovec payload{&version, 1};
    alignas(cmsghdr) char control[CMSG_SPACE(4 * sizeof(int))]{};
    msghdr message{};
    message.msg_iov = &payload; message.msg_iovlen = 1;
    if (count) {
        message.msg_control = control; message.msg_controllen = CMSG_SPACE(count * sizeof(int));
        auto cmsg = CMSG_FIRSTHDR(&message);
        cmsg->cmsg_level = SOL_SOCKET; cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(count * sizeof(int));
        std::memcpy(CMSG_DATA(cmsg), descriptors.data(), count * sizeof(int));
    }
    assert(sendmsg(transport[0], &message, MSG_NOSIGNAL) == 1);
    assert(fet::receive_channel(transport[1]) == -1);
    for (int fd : {transport[0], transport[1], pair[0], pair[1]}) close(fd);
}

int main() {
    int before = descriptors();
    int transport[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, transport) == 0);
    pid_t child = fork();
    assert(child >= 0);
    if (!child) {
        close(transport[0]);
        int calls = 0;
        fet::serve_settings(transport[1], [&] {
            return ++calls == 1 ? std::string("FET1\n\n\n0\n") : std::string("FET1\n") + std::string(64, 'a') + "\n0\n1\n";
        });
        close(transport[1]);
        _exit(calls == 2 ? 0 : 1);
    }
    close(transport[1]);
    assert(fet::channel_timeout(transport[0]));
    int fd = fet::receive_channel(transport[0]);
    assert(fd >= 0 && fd != transport[0]);
    assert(fcntl(fd, F_GETFD) & FD_CLOEXEC);
    ucred peer{}; socklen_t peer_size = sizeof(peer);
    assert(getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &peer_size) == 0 && peer.pid == child);
    close(transport[0]); // The provider's zygote-labelled connection is never used for settings.
    assert(fet::read_settings(fd) == "FET1\n\n\n0\n");
    assert(fet::read_settings(fd) == std::string("FET1\n") + std::string(64, 'a') + "\n0\n1\n");
    char invalid = 'W';
    assert(fet::transfer(fd, &invalid, 1, true));
    char reply;
    assert(recv(fd, &reply, 1, 0) == 0);
    close(fd);
    int status;
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);

    for (auto [version, count] : {std::pair{1, 0}, {2, 1}, {1, 2}, {1, 4}}) {
        reject_message(version, count);
        assert(descriptors() == before);
    }
    assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, transport) == 0);
    int ordinary = open("/dev/null", O_RDONLY | O_CLOEXEC);
    assert(ordinary >= 0 && fet::send_channel(transport[0], ordinary));
    assert(fet::receive_channel(transport[1]) == -1);
    close(ordinary); close(transport[0]); close(transport[1]);

    assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, transport) == 0);
    close(transport[0]);
    assert(fet::receive_channel(transport[1]) == -1);
    close(transport[1]);
    assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, transport) == 0);
    std::thread fragmented([&] {
        char request;
        assert(fet::transfer(transport[1], &request, 1, false) && request == 'R');
        for (char byte : std::string("\x09" "FET1\n\n\n0\n"))
            assert(fet::transfer(transport[1], &byte, 1, true));
        close(transport[1]);
    });
    fd = transport[0];
    assert(fet::read_settings(fd) == "FET1\n\n\n0\n");
    close(fd); fragmented.join();

    // Closed, malformed, oversized and partial replies permanently invalidate the channel.
    for (auto [length, text] : {std::pair<int, std::string>{0, ""}, {129, ""}, {12, "short"}, {1, "\r"}, {1, std::string(1, '\0')}}) {
        assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, transport) == 0);
        std::thread server([&] {
            char request;
            assert(fet::transfer(transport[1], &request, 1, false) && request == 'R');
            uint8_t size = length;
            assert(fet::transfer(transport[1], &size, 1, true));
            assert(fet::transfer(transport[1], text.data(), text.size(), true));
            close(transport[1]);
        });
        fd = transport[0];
        assert(!fet::read_settings(fd) && fd == -1);
        assert(!fet::read_settings(fd));
        server.join();
        assert(descriptors() == before);
    }
    assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, transport) == 0);
    close(transport[1]); fd = transport[0];
    assert(!fet::read_settings(fd) && fd == -1); // No SIGPIPE.
    assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, transport) == 0);
    timeval timeout{0, 10000};
    assert(setsockopt(transport[0], SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0);
    fd = transport[0];
    assert(!fet::read_settings(fd) && fd == -1);
    close(transport[1]);
    assert(fet::read_config("/dev/null").empty());
    assert(fet::read_config("/proc/self/fd/0").empty());
    assert(descriptors() == before);
}
