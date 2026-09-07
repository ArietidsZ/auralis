// hymt_core.cpp — C ABI. One owner per handle; no process-wide live set.

#include "hymt_core.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "llama.h"
#include "chat.h"
#include "hymt_tokens.h"

constexpr int kMaxNewTokens = 256;
constexpr int kCtxTokens = 2048;
constexpr int kBatchTokens = 256;
constexpr int kThreads = 4;
constexpr const char* kPinnedRevision = "1e411d8f5a1e23525fa3265dfb4bd76265465397";

struct hymt_handle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    std::atomic<bool> abortRequested{false};
    std::mutex generateMutex;
};

namespace {

void setErr(char* err, size_t errLen, const std::string& message) {
    if (err == nullptr || errLen == 0) return;
    std::snprintf(err, errLen, "%s", message.c_str());
}

bool isValidUtf8(const char* s) {
    if (s == nullptr) return false;
    const auto* p = reinterpret_cast<const unsigned char*>(s);
    while (*p != 0) {
        if (p[0] < 0x80) {
            ++p;
            continue;
        }
        int need = 0;
        unsigned int cp = 0;
        unsigned int minCp = 0;
        if ((p[0] & 0xE0) == 0xC0) {
            need = 1;
            cp = p[0] & 0x1F;
            minCp = 0x80;
        } else if ((p[0] & 0xF0) == 0xE0) {
            need = 2;
            cp = p[0] & 0x0F;
            minCp = 0x800;
        } else if ((p[0] & 0xF8) == 0xF0) {
            need = 3;
            cp = p[0] & 0x07;
            minCp = 0x10000;
        } else {
            return false;
        }
        for (int i = 1; i <= need; ++i) {
            if ((p[i] & 0xC0) != 0x80) return false;
            cp = (cp << 6) | (p[i] & 0x3F);
        }
        if (cp < minCp || cp > 0x10FFFF) return false;
        if (cp >= 0xD800 && cp <= 0xDFFF) return false;
        p += static_cast<size_t>(need) + 1;
    }
    return true;
}

std::string trimCopy(const std::string& s) {
    size_t a = 0;
    size_t b = s.size();
    while (a < b && (s[a] == ' ' || s[a] == '\t' || s[a] == '\n' || s[a] == '\r')) ++a;
    while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\t' || s[b - 1] == '\n' || s[b - 1] == '\r')) --b;
    return s.substr(a, b - a);
}

std::string lowerAscii(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (unsigned char c : s) {
        out.push_back(static_cast<char>(c >= 'A' && c <= 'Z' ? c + 32 : c));
    }
    return out;
}

// Official HY-MT1.5-1.8B card: English name + 中文名. ZH<=>XX instruction is
// Chinese, so the {target_language} slot uses the 中文名; XX<=>XX uses English.
struct Lang {
    std::string promptEn;
    std::string promptZh;
    bool zhFamily = false;
};

Lang canonicalLang(const std::string& raw) {
    const std::string trimmed = trimCopy(raw);
    const std::string key = lowerAscii(trimmed);
    auto make = [](const char* en, const char* zh, bool family) {
        Lang lang;
        lang.promptEn = en;
        lang.promptZh = zh;
        lang.zhFamily = family;
        return lang;
    };
    if (key == "unknown" || key == "auto" || key == "und") {
        return make("unknown", "未知", false);
    }
    if (key == "zh" || key == "zh-cn" || key == "zh-hans" || key == "zh-sg"
        || key == "chinese" || key == "simplified chinese"
        || trimmed == "中文" || trimmed == "汉语" || trimmed == "简体中文") {
        return make("Chinese", "中文", true);
    }
    if (key == "zh-hant" || key == "zh-tw" || key == "zh-hk"
        || key == "traditional chinese" || trimmed == "繁体中文" || trimmed == "繁體中文") {
        return make("Traditional Chinese", "繁体中文", true);
    }
    if (key == "yue" || key == "cantonese" || trimmed == "粤语" || trimmed == "粵語") {
        return make("Cantonese", "粤语", true);
    }
    if (key.size() >= 3 && key.compare(0, 3, "zh-") == 0) {
        return make("Chinese", "中文", true);
    }
    struct Row { const char* key; const char* en; const char* zh; };
    static const Row kMap[] = {
        {"en", "English", "英语"}, {"en-us", "English", "英语"}, {"en-gb", "English", "英语"},
        {"english", "English", "英语"},
        {"ja", "Japanese", "日语"}, {"japanese", "Japanese", "日语"},
        {"ko", "Korean", "韩语"}, {"korean", "Korean", "韩语"},
        {"de", "German", "德语"}, {"german", "German", "德语"}, {"deutsch", "German", "德语"},
        {"fr", "French", "法语"}, {"french", "French", "法语"},
        {"ru", "Russian", "俄语"}, {"russian", "Russian", "俄语"},
        {"pt", "Portuguese", "葡萄牙语"}, {"portuguese", "Portuguese", "葡萄牙语"},
        {"es", "Spanish", "西班牙语"}, {"spanish", "Spanish", "西班牙语"},
        {"it", "Italian", "意大利语"}, {"italian", "Italian", "意大利语"},
        {"ar", "Arabic", "阿拉伯语"}, {"arabic", "Arabic", "阿拉伯语"},
        {"th", "Thai", "泰语"}, {"thai", "Thai", "泰语"},
        {"vi", "Vietnamese", "越南语"}, {"vietnamese", "Vietnamese", "越南语"},
        {"id", "Indonesian", "印尼语"}, {"indonesian", "Indonesian", "印尼语"},
        {"ms", "Malay", "马来语"}, {"malay", "Malay", "马来语"},
        {"hi", "Hindi", "印地语"}, {"hindi", "Hindi", "印地语"},
        {"tr", "Turkish", "土耳其语"}, {"turkish", "Turkish", "土耳其语"},
        {"pl", "Polish", "波兰语"}, {"polish", "Polish", "波兰语"},
        {"nl", "Dutch", "荷兰语"}, {"dutch", "Dutch", "荷兰语"},
        {"cs", "Czech", "捷克语"}, {"czech", "Czech", "捷克语"},
        {"uk", "Ukrainian", "乌克兰语"}, {"ukrainian", "Ukrainian", "乌克兰语"},
    };
    for (const auto& row : kMap) {
        if (key == row.key) return make(row.en, row.zh, false);
    }
    if (trimmed == "英语" || trimmed == "英語") return make("English", "英语", false);
    if (trimmed == "日本語" || trimmed == "日语") return make("Japanese", "日语", false);
    if (trimmed == "한국어" || trimmed == "韩语" || trimmed == "韓語") return make("Korean", "韩语", false);
    Lang fallback;
    fallback.promptEn = trimmed.empty() ? raw : trimmed;
    fallback.promptZh = fallback.promptEn;
    fallback.zhFamily = false;
    return fallback;
}

std::string buildUserMessage(const std::string& text,
                             const std::string& sourceLanguage,
                             const std::string& targetLanguage,
                             const std::vector<std::string>& context) {
    const Lang src = canonicalLang(sourceLanguage);
    const Lang tgt = canonicalLang(targetLanguage);
    const bool zhPair = src.zhFamily || tgt.zhFamily;
    if (!context.empty()) {
        std::string msg;
        for (const auto& line : context) {
            msg += line;
            msg += "\n";
        }
        msg += "参考上面的信息，把下面的文本翻译成";
        msg += zhPair ? tgt.promptZh : tgt.promptEn;
        msg += "，注意不需要翻译上文，也不要额外解释：\n";
        msg += text;
        msg += "\n";
        return msg;
    }
    if (zhPair) {
        return "将以下文本翻译为" + tgt.promptZh
            + "，注意只需要输出翻译后的结果，不要额外解释：\n\n" + text;
    }
    return "Translate the following segment into " + tgt.promptEn
        + ", without additional explanation.\n\n" + text;
}

std::string applyChatTemplate(const llama_model* model,
                              const std::string& userMessage) {
    if (llama_model_chat_template(model, /*name=*/nullptr) == nullptr) {
        throw std::runtime_error(
            "model GGUF has no chat template; unsupported for Hy-MT translation");
    }
    common_chat_templates_ptr tmpls = common_chat_templates_init(model, "");
    common_chat_templates_inputs inputs;
    common_chat_msg user;
    user.role = "user";
    user.content = userMessage;
    inputs.messages.push_back(user);
    inputs.add_generation_prompt = true;
    inputs.use_jinja = true;
    inputs.add_bos = false;
    inputs.enable_thinking = false;
    common_chat_params params = common_chat_templates_apply(tmpls.get(), inputs);
    if (params.prompt.empty()) {
        throw std::runtime_error("chat template application produced an empty prompt");
    }
    return params.prompt;
}

llama_token sampleGreedy(llama_context* ctx, const llama_vocab* vocab) {
    const float* logits = llama_get_logits_ith(ctx, -1);
    if (logits == nullptr) throw std::runtime_error("no logits available");
    const int nVocab = llama_vocab_n_tokens(vocab);
    llama_token best = 0;
    float bestVal = logits[0];
    if (!std::isfinite(bestVal)) throw std::runtime_error("non-finite logit at position 0");
    for (int t = 1; t < nVocab; ++t) {
        if (!std::isfinite(logits[t])) throw std::runtime_error("non-finite logits in row");
        if (logits[t] > bestVal) {
            bestVal = logits[t];
            best = t;
        }
    }
    return best;
}

int translateInternal(hymt_handle* h,
                      const char* text, const char* src, const char* tgt,
                      const char* const* context, size_t nContext,
                      char** outText, char* err, size_t errLen) {
    std::lock_guard<std::mutex> lock(h->generateMutex);
    if (h->model == nullptr || h->ctx == nullptr || h->vocab == nullptr) {
        setErr(err, errLen, "hymt_translate: handle has been released");
        return HYMT_ERR_INVALID;
    }
    if (h->abortRequested.load()) {
        setErr(err, errLen, "Hy-MT generation aborted by cancel request");
        return HYMT_ERR_ABORTED;
    }
    try {
        llama_memory_clear(llama_get_memory(h->ctx), /*data=*/true);

        std::vector<std::string> ctxLines;
        ctxLines.reserve(nContext);
        for (size_t i = 0; i < nContext; ++i) {
            ctxLines.emplace_back(context[i]);
        }

        const int32_t nCtx = llama_n_ctx(h->ctx);
        auto promptTokens = [&](const std::vector<std::string>& lines) {
            const std::string prompt =
                applyChatTemplate(h->model, buildUserMessage(text, src, tgt, lines));
            return hymt::tokenize(h->vocab, prompt, /*add_special=*/false);
        };

        std::vector<llama_token> tokens = promptTokens(ctxLines);
        while (!ctxLines.empty()
               && static_cast<int64_t>(tokens.size()) + kMaxNewTokens > nCtx) {
            ctxLines.erase(ctxLines.begin());
            tokens = promptTokens(ctxLines);
        }
        if (static_cast<int64_t>(tokens.size()) + kMaxNewTokens > nCtx) {
            setErr(err, errLen,
                   "prompt plus token budget exceeds n_ctx after dropping context");
            return HYMT_ERR_FAILED;
        }
        const int32_t nBatch = llama_n_batch(h->ctx);
        if (nBatch <= 0) {
            setErr(err, errLen, "invalid native batch capacity");
            return HYMT_ERR_FAILED;
        }

        size_t pos = 0;
        while (pos < tokens.size()) {
            if (h->abortRequested.load()) {
                setErr(err, errLen, "Hy-MT generation aborted by cancel request");
                return HYMT_ERR_ABORTED;
            }
            const int32_t chunk = static_cast<int32_t>(
                std::min<size_t>(static_cast<size_t>(nBatch), tokens.size() - pos));
            llama_batch batch = llama_batch_get_one(tokens.data() + pos, chunk);
            if (llama_decode(h->ctx, batch) != 0) {
                if (h->abortRequested.load()) {
                    setErr(err, errLen, "Hy-MT generation aborted by cancel request");
                    return HYMT_ERR_ABORTED;
                }
                setErr(err, errLen, "Hy-MT prefill decode failed");
                return HYMT_ERR_FAILED;
            }
            pos += static_cast<size_t>(chunk);
        }

        std::string output;
        bool hitEog = false;
        int generated = 0;
        for (; generated < kMaxNewTokens; ++generated) {
            if (h->abortRequested.load()) {
                setErr(err, errLen, "Hy-MT generation aborted by cancel request");
                return HYMT_ERR_ABORTED;
            }
            const llama_token next = sampleGreedy(h->ctx, h->vocab);
            if (llama_vocab_is_eog(h->vocab, next)) {
                hitEog = true;
                break;
            }
            output += hymt::tokenPiece(h->vocab, next);
            tokens.push_back(next);
            llama_batch batch = llama_batch_get_one(tokens.data() + pos, 1);
            if (llama_decode(h->ctx, batch) != 0) {
                if (h->abortRequested.load()) {
                    setErr(err, errLen, "Hy-MT generation aborted by cancel request");
                    return HYMT_ERR_ABORTED;
                }
                setErr(err, errLen, "Hy-MT decode failed");
                return HYMT_ERR_FAILED;
            }
            ++pos;
        }
        if (!hitEog) {
            setErr(err, errLen, "generation hit token budget without EOG");
            return HYMT_ERR_FAILED;
        }

        char* heap = static_cast<char*>(std::malloc(output.size() + 1));
        if (heap == nullptr) {
            setErr(err, errLen, "out of memory copying result");
            return HYMT_ERR_FAILED;
        }
        std::memcpy(heap, output.c_str(), output.size() + 1);
        *outText = heap;
        return HYMT_OK;
    } catch (const std::exception& e) {
        setErr(err, errLen, e.what());
        return HYMT_ERR_FAILED;
    } catch (...) {
        setErr(err, errLen, "unknown native error in hymt_translate");
        return HYMT_ERR_FAILED;
    }
}

}  // namespace

extern "C" {

const char* hymt_runtime_revision(void) {
    return kPinnedRevision;
}

int hymt_load(const char* model_path_utf8, hymt_handle** out,
              char* err, size_t errLen) {
    static std::once_flag backendInitialized;
    try {
        if (model_path_utf8 == nullptr || out == nullptr) {
            setErr(err, errLen, "hymt_load: null model path or out pointer");
            return HYMT_ERR_INVALID;
        }
        *out = nullptr;
        if (!isValidUtf8(model_path_utf8)) {
            setErr(err, errLen, "hymt_load: model path is not valid UTF-8");
            return HYMT_ERR_INVALID;
        }
        std::call_once(backendInitialized, llama_backend_init);

        llama_model_params modelParams = llama_model_default_params();
        modelParams.n_gpu_layers = 0;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(model_path_utf8, modelParams), llama_model_free);
        if (!model) {
            setErr(err, errLen, "Hy-MT model load failed");
            return HYMT_ERR_FAILED;
        }

        llama_context_params ctxParams = llama_context_default_params();
        ctxParams.n_ctx = kCtxTokens;
        ctxParams.n_batch = kBatchTokens;
        ctxParams.n_threads = kThreads;
        ctxParams.n_threads_batch = kThreads;
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(
            llama_init_from_model(model.get(), ctxParams), llama_free);
        if (!ctx) {
            setErr(err, errLen, "Hy-MT context init failed");
            return HYMT_ERR_FAILED;
        }

        auto handle = std::make_unique<hymt_handle>();
        handle->vocab = llama_model_get_vocab(model.get());
        if (handle->vocab == nullptr) {
            setErr(err, errLen, "model has no vocabulary");
            return HYMT_ERR_FAILED;
        }
        llama_set_abort_callback(ctx.get(), [](void* user) {
            return static_cast<hymt_handle*>(user)->abortRequested.load();
        }, handle.get());
        handle->model = model.release();
        handle->ctx = ctx.release();
        *out = handle.release();
        return HYMT_OK;
    } catch (const std::exception& e) {
        setErr(err, errLen, e.what());
        return HYMT_ERR_FAILED;
    } catch (...) {
        setErr(err, errLen, "unknown native error in hymt_load");
        return HYMT_ERR_FAILED;
    }
}

int hymt_translate(hymt_handle* h,
                   const char* utf8_text,
                   const char* utf8_source_language,
                   const char* utf8_target_language,
                   const char* const* utf8_context, size_t n_context,
                   char** out_text,
                   char* err, size_t err_len) {
    if (out_text != nullptr) *out_text = nullptr;
    if (h == nullptr || utf8_text == nullptr || utf8_source_language == nullptr ||
        utf8_target_language == nullptr || out_text == nullptr) {
        setErr(err, err_len, "hymt_translate: null required argument");
        return HYMT_ERR_INVALID;
    }
    if (!isValidUtf8(utf8_text) || !isValidUtf8(utf8_source_language) ||
        !isValidUtf8(utf8_target_language)) {
        setErr(err, err_len, "hymt_translate: input is not valid UTF-8");
        return HYMT_ERR_INVALID;
    }
    if (n_context > 0 && utf8_context == nullptr) {
        setErr(err, err_len, "hymt_translate: n_context > 0 but context is null");
        return HYMT_ERR_INVALID;
    }
    for (size_t i = 0; i < n_context; ++i) {
        if (utf8_context[i] == nullptr) {
            setErr(err, err_len, "hymt_translate: context line is null");
            return HYMT_ERR_INVALID;
        }
        if (!isValidUtf8(utf8_context[i])) {
            setErr(err, err_len, "hymt_translate: context line is not valid UTF-8");
            return HYMT_ERR_INVALID;
        }
    }
    return translateInternal(h, utf8_text, utf8_source_language,
                             utf8_target_language, utf8_context, n_context,
                             out_text, err, err_len);
}

void hymt_cancel(hymt_handle* h) {
    if (h == nullptr) return;
    h->abortRequested.store(true);
}

void hymt_release(hymt_handle* h) {
    if (h == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(h->generateMutex);
        if (h->ctx != nullptr) {
            llama_free(h->ctx);
            h->ctx = nullptr;
        }
        if (h->model != nullptr) {
            llama_model_free(h->model);
            h->model = nullptr;
        }
    }
    delete h;
}

void hymt_free_string(char* s) {
    std::free(s);
}

}  // extern "C"
