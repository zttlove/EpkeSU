#ifndef KSU_COMPAT_H
#define KSU_COMPAT_H

#include <linux/fs.h>
#include <linux/file.h>

static inline void ksu_close_fd(int fd)
{
    if (fd >= 0) {
        struct file *filp = fget(fd);
        if (filp)
            filp_close(filp, NULL);
    }
}

#endif
