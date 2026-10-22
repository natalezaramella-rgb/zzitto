// Test della logica condivisa (Spec §5.2, §5.9, §5.11, §7). Aggiunto il 22/10/2026 alle 11:05.
#include <vector>

#include "zz_common.h"
#include "zz_test.h"

int main() {
    using zz::apply_voice_commands;

    // Comandi vocali (Spec §5.9)
    ZZ_CHECK_EQ_STR(apply_voice_commands("ciao a capo come stai").text, "ciao\ncome stai");
    ZZ_CHECK_EQ_STR(apply_voice_commands("primo nuovo paragrafo secondo").text, "primo\n\nsecondo");
    ZZ_CHECK_EQ_STR(apply_voice_commands("ciao virgola come stai punto").text, "ciao, come stai.");
    ZZ_CHECK_EQ_STR(apply_voice_commands("a che punto siamo").text, "a che punto siamo");
    ZZ_CHECK_EQ_STR(apply_voice_commands("nota due punti latte").text, "nota: latte");
    {
        auto r = apply_voice_commands("Prima frase. Seconda frase. cancella l'ultima frase terza");
        ZZ_CHECK_EQ_STR(r.text, "Prima frase. terza");
        ZZ_CHECK(r.actions == ZZ_ACTION_NONE);
    }
    {
        auto r = apply_voice_commands("cancella l'ultima frase");
        ZZ_CHECK_EQ_STR(r.text, "");
        ZZ_CHECK(r.actions & ZZ_ACTION_DELETE_PREVIOUS_SENTENCE);
    }
    {
        auto r = apply_voice_commands("Annulla.");
        ZZ_CHECK(r.actions & ZZ_ACTION_UNDO_LAST_INSERT);
        ZZ_CHECK_EQ_STR(r.text, "");
    }
    {
        auto r = apply_voice_commands("arrivo tra dieci minuti invia");
        ZZ_CHECK_EQ_STR(r.text, "arrivo tra dieci minuti");
        ZZ_CHECK(r.actions & ZZ_ACTION_SEND);
    }

    // Scorciatoie (Spec §5.11)
    std::vector<std::pair<std::string, std::string>> sc = {{"firma", "Natale\nZaramella Consulting"}};
    std::string exp;
    ZZ_CHECK(zz::match_shortcut(sc, " Firma. ", &exp));
    ZZ_CHECK_EQ_STR(exp, "Natale\nZaramella Consulting");
    ZZ_CHECK(!zz::match_shortcut(sc, "metti la firma", &exp));

    // Pulizia di base
    ZZ_CHECK_EQ_STR(zz::basic_cleanup("ehm allora questo questo è un test", ZZ_MODE_CLEAN), "Questo è un test.");

    // VAD (Spec §5.2): 0,5 s di parlato e poi 2,5 s di silenzio
    {
        zz_vad *v = zz_vad_create(2000);
        std::vector<float> speech(8000), silence(16000 * 5 / 2, 0.0f);
        for (size_t i = 0; i < speech.size(); ++i) speech[i] = (i % 40 < 20) ? 0.3f : -0.3f;
        ZZ_CHECK(zz_vad_process(v, silence.data(), 16000) == 0);
        ZZ_CHECK(zz_vad_process(v, speech.data(), speech.size()) == 0);
        ZZ_CHECK(zz_vad_has_speech(v));
        ZZ_CHECK(zz_vad_process(v, silence.data(), silence.size()) == 1);
        zz_vad_reset(v);
        ZZ_CHECK(!zz_vad_has_speech(v));
        zz_vad_destroy(v);
    }

    // SHA-256 (Spec §7): vettore noto per "abc"
    {
        const char *path = "zz_sha_test.tmp";
        FILE *f = std::fopen(path, "wb");
        std::fwrite("abc", 1, 3, f);
        std::fclose(f);
        char hex[65] = {0};
        ZZ_CHECK(zz_sha256_file(path, hex) == ZZ_OK);
        ZZ_CHECK_EQ_STR(hex, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        std::remove(path);
        ZZ_CHECK(zz_sha256_file("inesistente.bin", hex) == ZZ_ERR_MODEL_LOAD);
    }

    ZZ_TEST_MAIN_END();
}
