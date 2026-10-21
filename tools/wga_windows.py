"""Totals the WGA 10-second windows of a log: chunks, seconds, chunk-weighted ms per stage, layers, cores."""
import re, sys
from collections import defaultdict
for path in sys.argv[1:]:
    text = open(path, encoding='utf-8', errors='replace').read().splitlines()
    chunks = secs = 0; stage = defaultdict(float); layers = [0.0, 0.0]; cpu = 0.0; cores = defaultdict(float)
    n = 0
    for i, line in enumerate(text):
        m = re.search(r'WGA (\d+)s: ([\d,]+) new chunks .*CPU ([\d.]+) of', line)
        if not m:
            continue
        s, n = int(m.group(1)), int(m.group(2).replace(',', ''))
        if n == 0:
            continue
        chunks += n; secs += s; cpu += float(m.group(3)) * s
        work = next((l for l in text[i+1:i+3] if 'WGA work per chunk' in l), '')
        for name, ms in re.findall(r'(?:: |, )([a-z ]+?) ([\d.]+) \(\d+%\)', work):
            stage[name.strip()] += float(ms) * n
        lm = re.search(r'([\d.]+) filled, ([\d.]+) needed', work)
        if lm:
            layers[0] += float(lm.group(1)) * n; layers[1] += float(lm.group(2)) * n
        cl = next((l for l in text[i+1:i+7] if 'WGA cores by thread group' in l), '')
        for name, c in re.findall(r'(?:: |, )([^,:]+?) ([\d.]+)(?=,|$)', cl):
            cores[name.strip()] += float(c) * s
    print('==', path.split('/')[-1], '%d new chunks in %d s of windows = %.1f/s, CPU %.1f threads' % (chunks, secs, chunks / secs, cpu / secs))
    total = sum(stage.values()) / chunks
    print('   work per chunk (ms): ' + ', '.join('%s %.1f' % (k, v / chunks) for k, v in sorted(stage.items(), key=lambda kv: -kv[1])) + ' | total %.1f' % total)
    print('   layers filled %.0f, needed %.0f' % (layers[0] / chunks, layers[1] / chunks))
    print('   cores: ' + ', '.join('%s %.1f' % (k, v / secs) for k, v in sorted(cores.items(), key=lambda kv: -kv[1])[:5]))
