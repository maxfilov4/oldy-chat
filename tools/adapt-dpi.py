"""Small transport adaptation of pinned MIT ByeDPI. Desync algorithms stay upstream."""
from pathlib import Path
import sys

root=Path(sys.argv[1])
p=root/'proxy.c'
text=p.read_text()
text='#include <sys/un.h>\n#include <sys/stat.h>\n#include <sys/prctl.h>\n'+text
old='int listen_socket(const union sockaddr_u *srv)\n{'
assert text.count(old)==1
text=text.replace(old,old+'''
    const char *path = getenv("OLDI_DPI_SOCKET");
    if (!path || strlen(path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) return -1;
    struct sockaddr_un local = { .sun_family = AF_UNIX };
    strcpy(local.sun_path, path);
    int fd = nb_socket(AF_UNIX, SOCK_STREAM);
    if (fd < 0) return -1;
    unlink(path);
    mode_t mask = umask(0077);
    int status = bind(fd, (struct sockaddr *)&local, sizeof(local));
    umask(mask);
    if (status || listen(fd, 64)) { close(fd); return -1; }
    return fd;
#if 0
''')
old='return srvfd;\n}\n\n\nint run'
assert text.count(old)==1
text=text.replace(old,'return srvfd;\n#endif\n}\n\n\nint run')
old='if (setsockopt(c, IPPROTO_TCP, TCP_NODELAY,'
assert text.count(old)==1
text=text.replace(old,'if (client.sa.sa_family != AF_UNIX && setsockopt(c, IPPROTO_TCP, TCP_NODELAY,')
# Process cannot survive a dead VPN service; no public listener is ever created.
text=text.replace('int run(const union sockaddr_u *srv)\n{','int run(const union sockaddr_u *srv)\n{\n    prctl(PR_SET_PDEATHSIG, SIGTERM);\n    if (getppid() == 1) return -1;')
p.write_text(text)
# The upstream protector treats any response as success. Require an explicit success byte.
p=root/'extend.c';text=p.read_text();needle='if (recv(fd, buf, 1, 0) < 1) {'
assert text.count(needle)==1
text=text.replace(needle,'if (recv(fd, buf, 1, 0) < 1 || buf[0] != 1) {')
p.write_text(text)
