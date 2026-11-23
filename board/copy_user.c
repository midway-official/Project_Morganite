/* PLV3 controls for the archived Linux copy probes on fixed physical pages. */
extern void install_page_pair(unsigned, unsigned, unsigned, unsigned);
extern int write_message(const char *, unsigned);
extern int enter_user(unsigned pc);
#define MESSAGE(s) write_message(s, sizeof(s) - 1)

struct probe {
	const char *name;
	unsigned entry;
	unsigned text_pa;
};
#include "user_probes.h"

static void map_pair(unsigned slot, unsigned va, unsigned pa, unsigned writable)
{
	/* 4 KiB pages, global, coherent cached, PLV3, valid. */
	unsigned elo = (pa >> 4) | 0x5d | (writable << 1);
	install_page_pair((12u << 24) | slot, va, elo, elo + 0x100);
}

static void zero_pair(unsigned pa)
{
	volatile unsigned *words = (volatile unsigned *)(0xa0000000u | pa);
	for (unsigned i = 0; i < 8192 / sizeof(*words); ++i)
		words[i] = 0;
}

void run_bare(void)
{
	unsigned ecfg, tcfg, crmd;
	asm volatile("csrrd %0, 4" : "=r"(ecfg));
	asm volatile("csrrd %0, 0x41" : "=r"(tcfg));
	asm volatile("csrrd %0, 0" : "=r"(crmd));
	if (ecfg || tcfg || (crmd & 31) != 0x10) {
		MESSAGE("USER_SETUP_FAILED\n");
		return;
	}
	MESSAGE("USER_BEGIN plv=3 irq=0 timer=0 static_tlb=4K\n");
	for (unsigned round = 0; round < 2; ++round) {
		for (unsigned trial = 0; trial < 3; ++trial) {
			const struct probe *p = &probes[(trial + round) % 3];
			map_pair(0, 0x10000, p->text_pa, 0);
			for (unsigned i = 0; i < 6; ++i) {
				unsigned pa = 0x01200000 + i * 8192;
				zero_pair(pa);
				map_pair(i + 1, 0x12000 + i * 8192, pa, 1);
			}
			zero_pair(0x01400000);
			map_pair(7, 0x40000000, 0x01400000, 1);
			asm volatile("dbar 0\n ibar 0" ::: "memory");
			unsigned len = 0;
			while (p->name[len])
				++len;
			write_message(p->name, len);
			MESSAGE(" ");
			int rc = enter_user(p->entry);
			if (rc == 0)
				MESSAGE("USER_RC 0\n");
			else if (rc == 1)
				MESSAGE("USER_RC 1\n");
			else {
				MESSAGE("USER_UNEXPECTED_TRAP\n");
				return;
			}
		}
	}
	MESSAGE("USER_END\n");
}
