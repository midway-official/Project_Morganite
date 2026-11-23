/* One thread, two aligned destination bytes per inner iteration. The expected
 * pattern for each output is calculated independently of the source buffer. */
extern void copy_match(unsigned char *, const unsigned char *, unsigned);
extern int write_message(const char *, unsigned);

enum { output_size = 32768 };
#ifndef COPY_STANDALONE
static unsigned char area[8192 + 256 + output_size] __attribute__((aligned(8192)));
#endif
static const unsigned distances[] = {3, 4, 5, 7, 8, 15, 31, 63, 129, 511, 4095, 8191};
static const unsigned offsets[] = {0, 2, 30, 62, 126, 254};

static unsigned char pattern(unsigned index, unsigned salt)
{
	return (index * 29u + (index >> 3) + salt) & 255u;
}

static void hex(unsigned value)
{
	static const char digits[] = "0123456789abcdef";
	char field[9];
	unsigned i;

	field[8] = ' ';
	for (i = 8; i; value >>= 4)
		field[--i] = digits[value & 15];
	write_message(field, sizeof(field));
}

#ifdef COPY_STANDALONE
int run_probe(unsigned char *area, void (*copy_match)(unsigned char *, const unsigned char *, unsigned))
#else
int run_probe(void)
#endif
{
	unsigned pass, o, d, i, phase;
	unsigned bytes = 0;
	for (pass = 0; pass < 4; pass++) {
		for (o = 0; o < sizeof(offsets) / sizeof(offsets[0]); o++) {
			unsigned char *dst = area + 8192 + offsets[o];
			for (d = 0; d < sizeof(distances) / sizeof(distances[0]); d++) {
				unsigned distance = distances[d];
				unsigned salt = 0x53u + 17u * pass + offsets[o];
				unsigned char *seed = dst - distance;

				for (i = 0; i < output_size; i++)
					dst[i] = 0xa5;
				for (i = 0; i < distance; i++)
					seed[i] = pattern(i, salt);
				copy_match(dst, seed, output_size / 2);
				for (i = 0, phase = 0; i < output_size; i++) {
					unsigned char expected = pattern(phase, salt);
					if (dst[i] != expected) {
						static const char message[] = "COPY_FAIL pass/offset/distance/index/expected/actual ";
						write_message(message, sizeof(message) - 1);
						hex(pass); hex(offsets[o]); hex(distance); hex(i);
						hex(expected); hex(dst[i]); write_message("\n", 1);
						return 1;
					}
					if (++phase == distance)
						phase = 0;
				}
				bytes += output_size;
			}
		}
	}
	static const char message[] = "COPY_PASS bytes_hex ";
	write_message(message, sizeof(message) - 1);
	hex(bytes); write_message("\n", 1);
	return 0;
}
