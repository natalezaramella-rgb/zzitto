# Genera un modello "phi3" minuscolo (pesi casuali, vocabolario Phi-3 di llama.cpp) per i
# test di integrazione di llama.cpp senza scaricare Phi-4-mini (Spec §12).
# Aggiunto il 22/10/2026 alle 11:37. Uso:
#   python3 -I make_tiny_phi3.py <llama.cpp/gguf-py> <ggml-vocab-phi-3.gguf> <uscita.gguf>
# Il testo generato è privo di senso: il test verifica caricamento, template, cache,
# timeout e annullamento, non la qualità.
import sys
gguf_py, vocab_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
sys.path.insert(0, gguf_py)
import numpy as np
import gguf
r = gguf.GGUFReader(vocab_path)
def field(name):
    f = r.fields[name]
    return f
def strval(name):
    f = field(name); return bytes(f.parts[f.data[0]]).decode()
def arr(name):
    f = field(name); return [f.parts[i] for i in f.data]
tokens = [bytes(p).decode('utf-8', errors='replace') for p in arr('tokenizer.ggml.tokens')]
scores = [float(p[0]) for p in arr('tokenizer.ggml.scores')]
types = [int(p[0]) for p in arr('tokenizer.ggml.token_type')]
def u32(name):
    f = field(name); return int(f.parts[f.data[0]][0])
w = gguf.GGUFWriter(out_path, 'phi3')
n_embd, n_ff, n_layer, n_head = 64, 128, 2, 4
n_vocab = len(tokens)
w.add_context_length(4096)
w.add_embedding_length(n_embd)
w.add_feed_forward_length(n_ff)
w.add_block_count(n_layer)
w.add_head_count(n_head)
w.add_head_count_kv(n_head)
w.add_rope_dimension_count(n_embd // n_head)
w.add_layer_norm_rms_eps(1e-5)
w.add_file_type(1)
w.add_tokenizer_model(strval('tokenizer.ggml.model'))
w.add_token_list(tokens)
w.add_token_scores(scores)
w.add_token_types(types)
for k, fn in [('tokenizer.ggml.bos_token_id', w.add_bos_token_id), ('tokenizer.ggml.eos_token_id', w.add_eos_token_id),
              ('tokenizer.ggml.unknown_token_id', w.add_unk_token_id), ('tokenizer.ggml.padding_token_id', w.add_pad_token_id)]:
    if k in r.fields: fn(u32(k))
w.add_add_bos_token(False)
rng = np.random.default_rng(0)
def t(name, shape):
    w.add_tensor(name, (rng.standard_normal(shape) * 0.02).astype(np.float16))
t('token_embd.weight', (n_vocab, n_embd))
w.add_tensor('output_norm.weight', np.ones(n_embd, dtype=np.float32))
t('output.weight', (n_vocab, n_embd))
for i in range(n_layer):
    w.add_tensor(f'blk.{i}.attn_norm.weight', np.ones(n_embd, dtype=np.float32))
    t(f'blk.{i}.attn_qkv.weight', (3 * n_embd, n_embd))
    t(f'blk.{i}.attn_output.weight', (n_embd, n_embd))
    w.add_tensor(f'blk.{i}.ffn_norm.weight', np.ones(n_embd, dtype=np.float32))
    t(f'blk.{i}.ffn_up.weight', (2 * n_ff, n_embd))
    t(f'blk.{i}.ffn_down.weight', (n_embd, n_ff))
w.write_header_to_file(); w.write_kv_data_to_file(); w.write_tensors_to_file(); w.close()
print('ok', n_vocab)
