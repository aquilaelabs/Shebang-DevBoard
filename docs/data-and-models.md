# Data and models

How the word list, the word statistics and the two neural models are rebuilt. What goes into each, and
how each was measured, is in [decisions.md](decisions.md); the sources and licences are in
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

Regenerate the word lists (the regular words and the three packs; see "Dictionaries in packs" in
decisions.md) from a SCOWL release, then the n-gram model from Tatoeba's English sentence export
(<https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2>) and Common Voice's English
sentence collection (the `server/data/en/*.txt` files of <https://github.com/common-voice/common-voice>, in
one folder). `--exclude` keeps the FUTO test and dev sentences out of the counts, so the real-swipe benchmark
stays fair:

```sh
tools/build_wordlist.py /path/to/scowl-2020.12.07
tools/tech_corpus.py tech.txt python=cpython/Doc rustbook=book/src kotlin=kotlin-web-site/docs/topics \
    docker=docs/content k8s=website/content/en/docs freebsd=freebsd-doc/documentation/content/en/books/handbook
tools/build_ngrams.py /path/to/eng_sentences.tsv.bz2 --cv /path/to/cv-en --tech tech.txt --tech-weight 1 \
    --exclude /path/to/futo/test.jsonl /path/to/futo/dev.jsonl
```

The glide model is trained on the GPU with PyTorch (`python-ml` from the toolchain store's `pytorch`) on
FUTO's training split, then exported as the app's asset with test vectors for the Kotlin port
(`GlideModelTest`); `score.py` picks its weight on the dev split from a decoder dump
(`FUTO_DUMP=... ./gradlew testDebugUnitTest --tests '*FutoSwipesTest.futoDump'`):

```sh
python-ml tools/glide_model/prep.py /path/to/futo/train.jsonl train.npz
python-ml tools/glide_model/prep.py /path/to/futo/dev.jsonl dev.npz
bb gpu run -- python-ml tools/glide_model/train.py train.npz dev.npz glide.pt --hidden 64 --conv 64
python-ml tools/glide_model/score.py glide.pt cands-dev.jsonl cands-test.jsonl
python-ml tools/glide_model/export.py glide.pt app/src/main/assets/glide/glide_model.bin --vectors dev.npz app/src/test/resources/glide/glide_model_vectors.txt
```

The next-word model is trained the same way on the n-gram model's sentences plus English Wikinews (its
sentences go in the Common Voice folder), then exported with 8-bit word tables:

```sh
python3 tools/lm_model/wikinews.py enwikinews-latest-pages-articles.xml.bz2 /path/to/cv-en/zz_wikinews.txt
python-ml tools/lm_model/prep.py /path/to/eng_sentences.tsv /path/to/cv-en corpus.npz --exclude /path/to/futo/test.jsonl /path/to/futo/dev.jsonl
# (--tech tech.txt [--vocab 33000] adds the technical documentation; the shipped network was trained without
#  it: see "Retraining the next-word network" in decisions.md)
bb gpu run -- python-ml tools/lm_model/train.py corpus.npz lm.pt --futo /path/to/futo/test.jsonl --emb 128 --hidden 384 --epochs 8 --batch 128 --bptt 32
python-ml tools/lm_model/glide_context.py lm.pt cands-dev.jsonl cands-test.jsonl
python-ml tools/lm_model/export.py lm.pt app/src/main/assets/dict/en_next_word.bin --vectors app/src/test/resources/dict/next_word_vectors.txt
```
