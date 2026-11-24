// SPDX-License-Identifier: GPL-2.0
#include <linux/dma-mapping.h>
#include <linux/fs.h>
#include <linux/iopoll.h>
#include <linux/miscdevice.h>
#include <linux/module.h>
#include <linux/mutex.h>
#include <linux/of.h>
#include <linux/platform_device.h>
#include <linux/uaccess.h>
#include "morganite.h"

#define MG_BYTES SZ_2M
#define MG_BUSY BIT(0)
struct morganite {
    struct miscdevice misc;
    struct mutex lock;
    struct device *dev;
    void __iomem *regs;
    void *cpu;
    dma_addr_t dma;
    u32 caps, features;
    bool opened;
};

static u32 reg_read(struct morganite *m, unsigned int offset)
{
    return readl(m->regs + offset);
}

static int mg_open(struct inode *inode, struct file *file)
{
    struct morganite *m = container_of(file->private_data, struct morganite, misc);
    int err = 0;
    mutex_lock(&m->lock);
    if (m->opened || (reg_read(m, 0x14) & MG_BUSY))
        err = -EBUSY;
    else {
        m->opened = true;
        file->private_data = m;
    }
    mutex_unlock(&m->lock);
    return err;
}

static int mg_release(struct inode *inode, struct file *file)
{
    struct morganite *m = file->private_data;
    u32 value;
    mutex_lock(&m->lock);
    if (reg_read(m, 0x14) & MG_BUSY) {
        writel(1, m->regs + 0x2c);
        if (readl_poll_timeout(m->regs + 0x14, value, !(value & MG_BUSY), 100, 5000000))
            dev_err(m->dev, "GPU still draining after close\n");
    }
    m->opened = false;
    mutex_unlock(&m->lock);
    return 0;
}

static ssize_t mg_read(struct file *f, char __user *buf, size_t len, loff_t *pos)
{
    struct morganite *m = f->private_data;
    ssize_t n = -EBUSY;
    mutex_lock(&m->lock);
    if (!(reg_read(m, 0x14) & MG_BUSY)) {
        dma_rmb();
        n = simple_read_from_buffer(buf, len, pos, m->cpu, MG_BYTES);
    }
    mutex_unlock(&m->lock);
    return n;
}

static ssize_t mg_write(struct file *f, const char __user *buf, size_t len, loff_t *pos)
{
    struct morganite *m = f->private_data;
    ssize_t n = -EBUSY;
    mutex_lock(&m->lock);
    if (!(reg_read(m, 0x14) & MG_BUSY))
        n = simple_write_to_buffer(m->cpu, MG_BYTES, pos, buf, len);
    mutex_unlock(&m->lock);
    return n;
}

static long mg_ioctl(struct file *f, unsigned int cmd, unsigned long arg)
{
    struct morganite *m = f->private_data;
    void __user *ptr = (void __user *)arg;
    struct mg_info info = {0};
    struct mg_submit job;
    struct mg_submit_ro ro = {0};
    struct mg_sm_counters counters = {0};
    struct mg_status s = {0};
    unsigned int i, j;
    int err = 0;
    mutex_lock(&m->lock);
    switch (cmd) {
    case MG_INFO:
        info.id = reg_read(m, 0);
        info.abi = reg_read(m, 4);
        info.caps = m->caps;
        info.device_base = m->dma;
        info.bytes = MG_BYTES;
        info.last_sequence = reg_read(m, 0x28);
        if (copy_to_user(ptr, &info, sizeof(info))) err = -EFAULT;
        break;
    case MG_SUBMIT:
    case MG_SUBMIT_RO:
        if (cmd == MG_SUBMIT_RO) {
            if (!(m->features & 2)) { err = -EOPNOTSUPP; break; }
            if (copy_from_user(&ro, ptr, sizeof(ro))) { err = -EFAULT; break; }
            if (ro.offset > MG_BYTES || ro.length > MG_BYTES - ro.offset) { err = -EINVAL; break; }
            job = ro.job;
        } else if (copy_from_user(&job, ptr, sizeof(job))) { err = -EFAULT; break; }
        if (reg_read(m, 0x14) & MG_BUSY) { err = -EBUSY; break; }
        if ((job.descriptor_offset & 3) || job.descriptor_offset > MG_BYTES - 32 ||
            !job.sequence || reg_read(m, 0x28) == job.sequence || !job.sm_mask ||
            ((u64)job.sm_mask >> (m->caps & 255))) { err = -EINVAL; break; }
        if (m->features & 2) {
            writel(m->dma + ro.offset, m->regs + 0x54);
            writel(m->dma + ro.offset + ro.length, m->regs + 0x58);
        }
        dma_wmb();
        writel(m->dma + job.descriptor_offset, m->regs + 0x0c);
        writel(job.sm_mask, m->regs + 0x38);
        wmb();
        writel(job.sequence, m->regs + 0x10);
        if (reg_read(m, 0x28) != job.sequence) err = -EIO;
        break;
    case MG_STATUS:
        s.state = reg_read(m, 0x14);
        s.completed = reg_read(m, 0x18);
        s.fault = reg_read(m, 0x1c);
        s.address = reg_read(m, 0x20);
        s.failed_sequence = reg_read(m, 0x24);
        dma_rmb();
        s.cycles = reg_read(m, 0x3c);
        s.read_bursts = reg_read(m, 0x40);
        s.write_bursts = reg_read(m, 0x44);
        s.read_bytes = reg_read(m, 0x48);
        s.write_bytes = reg_read(m, 0x4c);
        for (i = 0; i < min_t(unsigned int, m->caps & 255, ARRAY_SIZE(s.sm)); ++i)
            for (j = 0; j < 5; ++j)
                s.sm[i][j] = reg_read(m, 0x100 + 0x20*i + 4*j);
        if (copy_to_user(ptr, &s, sizeof(s))) err = -EFAULT;
        break;
    case MG_SM_COUNTERS:
        if (get_user(counters.index, (__u32 __user *)ptr)) { err = -EFAULT; break; }
        if (counters.index >= (m->caps & 255)) { err = -EINVAL; break; }
        for (j = 0; j < 5; ++j)
            counters.values[j] = reg_read(m, 0x100 + 32*counters.index + 4*j);
        if (m->features & 3)
            for (j = 0; j < 4; ++j)
                counters.cache[j] = reg_read(m, 0x600 + 16*counters.index + 4*j);
        if (copy_to_user(ptr, &counters, sizeof(counters))) err = -EFAULT;
        break;
    case MG_CANCEL:
        writel(1, m->regs + 0x2c);
        break;
    default:
        err = -ENOTTY;
    }
    mutex_unlock(&m->lock);
    return err;
}

static loff_t mg_seek(struct file *file, loff_t offset, int whence)
{
    return fixed_size_llseek(file, offset, whence, MG_BYTES);
}
static const struct file_operations mg_fops = {
    .owner = THIS_MODULE, .open = mg_open, .release = mg_release, .llseek = mg_seek,
    .read = mg_read, .write = mg_write, .unlocked_ioctl = mg_ioctl,
};
static int mg_probe(struct platform_device *pdev)
{
    struct device *dev = &pdev->dev;
    struct morganite *m;
    int err;
    m = devm_kzalloc(dev, sizeof(*m), GFP_KERNEL);
    if (!m) return -ENOMEM;
    m->dev = dev;
    m->regs = devm_platform_ioremap_resource(pdev, 0);
    if (IS_ERR(m->regs)) return PTR_ERR(m->regs);
    if (reg_read(m, 0) != 0x4d4f5247 || reg_read(m, 4) != 0x10000) return -ENODEV;
    m->caps = reg_read(m, 8);
    if (!(m->caps & 255) || (m->caps & 255) > 32) return -EINVAL;
    /* Legacy FPGA builds return SLVERR on an unknown MMIO register. */
    if (of_property_read_bool(dev->of_node, "morganite,feature-register"))
        m->features = reg_read(m, 0x50);
    err = dma_set_mask_and_coherent(dev, DMA_BIT_MASK(32));
    if (err) return err;
    m->cpu = dmam_alloc_coherent(dev, MG_BYTES, &m->dma, GFP_KERNEL);
    if (!m->cpu) return -ENOMEM;
    if (m->dma < 0x01000000 || (u64)m->dma + MG_BYTES > 0x1fe00000) return -ERANGE;
    if (reg_read(m, 0x14) & MG_BUSY) return -EBUSY;
    writel(m->dma, m->regs + 0x30);
    writel(m->dma + MG_BYTES, m->regs + 0x34);
    if (reg_read(m, 0x30) != m->dma || reg_read(m, 0x34) != m->dma + MG_BYTES) return -EIO;
    mutex_init(&m->lock);
    m->misc = (struct miscdevice){.minor = MISC_DYNAMIC_MINOR, .name = "morganite",
                                  .fops = &mg_fops, .parent = dev, .mode = 0600};
    platform_set_drvdata(pdev, m);
    dev_info(dev, "ABI 1.0, %u SM, coherent DMA %pad, %u bytes, CPU mapping %px\n",
             m->caps & 255, &m->dma, MG_BYTES, m->cpu);
    return misc_register(&m->misc);
}

static int mg_remove(struct platform_device *pdev)
{
    struct morganite *m = platform_get_drvdata(pdev);
    misc_deregister(&m->misc);
    return 0;
}
static const struct of_device_id mg_match[] = {{.compatible = "morganite,gpu-v1"}, {}};
static struct platform_driver mg_driver = {
    .driver = {.name = "morganite", .of_match_table = mg_match, .suppress_bind_attrs = true},
    .probe = mg_probe, .remove = mg_remove,
};
module_platform_driver(mg_driver);
MODULE_LICENSE("GPL");
