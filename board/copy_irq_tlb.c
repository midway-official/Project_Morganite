typedef void (*copy_fn)(unsigned char *, const unsigned char *, unsigned);
extern int run_probe(unsigned char *, copy_fn);
extern int write_message(const char *, unsigned);
extern void copy_match(unsigned char *, const unsigned char *, unsigned);
extern void copy_barrier(unsigned char *, const unsigned char *, unsigned);
extern void install_page_pair(unsigned, unsigned, unsigned, unsigned);
extern void enable_timer(void);
extern unsigned stop_timer(void);
#define MESSAGE(s) write_message(s, sizeof(s)-1)

static void map_pages(unsigned va, unsigned pa)
{
	for (unsigned i = 0; i < 8; i++) {
		unsigned even = ((pa + 8192 * i) >> 4) | 0x53;
		install_page_pair((12u << 24) | i, va + 8192 * i, even, even + 0x100);
	}
	asm volatile("dbar 0\n ibar 0" ::: "memory");
}

static void report_interrupts(unsigned count)
{
	char field[9];
	field[8] = '\n';
	for (int i = 7; i >= 0; i--, count >>= 4)
		field[i] = "0123456789abcdef"[count & 15];
	MESSAGE("INTERRUPTS ");
	write_message(field, sizeof(field));
}

void run_irq_tlb(void)
{
	unsigned char *mapped = (unsigned char *)0x40000000;
	for (unsigned i = 0; i < 2; i++) {
		unsigned offset = i * 0x10000;
		/* Direct data uses distinct pages from the statically mapped test. */
		MESSAGE("DIRECT_IRQ ");
		enable_timer();
		run_probe((unsigned char *)(0xa1200000 + offset), copy_match);
		report_interrupts(stop_timer());
		map_pages((unsigned)mapped, 0x01400000 + offset);
		MESSAGE("TLB_NO_IRQ ");
		run_probe(mapped, copy_match);
		MESSAGE("TLB_IRQ ");
		enable_timer();
		run_probe(mapped, copy_match);
		report_interrupts(stop_timer());
		MESSAGE("TLB_IRQ_DBAR ");
		enable_timer();
		run_probe(mapped, copy_barrier);
		report_interrupts(stop_timer());
	}
}
