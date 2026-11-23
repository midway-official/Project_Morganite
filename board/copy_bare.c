typedef void (*copy_fn)(unsigned char *, const unsigned char *, unsigned);
extern int run_probe(unsigned char *, copy_fn);
extern int write_message(const char *, unsigned);
extern void copy_match(unsigned char *, const unsigned char *, unsigned);
extern void copy_barrier(unsigned char *, const unsigned char *, unsigned);
#define MESSAGE(s) write_message(s, sizeof(s)-1)

void run_bare(void)
{
	unsigned crmd, ecfg, tcfg, dmw0, dmw1;
	unsigned i;

	asm volatile("csrrd %0, 0" : "=r"(crmd));
	asm volatile("csrrd %0, 4" : "=r"(ecfg));
	asm volatile("csrrd %0, 0x41" : "=r"(tcfg));
	asm volatile("csrrd %0, 0x180" : "=r"(dmw0));
	asm volatile("csrrd %0, 0x181" : "=r"(dmw1));
	if ((crmd & 0x1f) != 0x10 || ecfg || tcfg || dmw0 != 0xa0000011 || dmw1 != 0x80000001) {
		MESSAGE("BARE_SETUP_FAILED\n");
		return;
	}
	MESSAGE("BARE_BEGIN plv=0 irq=0 timer=0 cached=0xa0000011 uncached=0x80000001\n");
	for (i = 0; i < 2; i++) {
		/* Each mapping gets a distinct physical region. Code and stack stay cached. */
		unsigned offset = i * 0x10000;
		MESSAGE("CACHED_ORIGINAL ");
		run_probe((unsigned char *)(0xa1000000 + offset), copy_match);
		MESSAGE("CACHED_DBAR ");
		run_probe((unsigned char *)(0xa1020000 + offset), copy_barrier);
		MESSAGE("UNCACHED_ORIGINAL ");
		run_probe((unsigned char *)(0x81040000 + offset), copy_match);
	}
	MESSAGE("BARE_END\n");
}
