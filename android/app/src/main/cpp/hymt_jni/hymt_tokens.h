#pragma once

#include "llama.h"
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

namespace hymt {

inline std::vector<llama_token> tokenize(const llama_vocab* vocab, const std::string& text,
                                         bool add_special = true) {
    if (text.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        throw std::runtime_error("prompt is too large to tokenize");
    }
    const int32_t length = static_cast<int32_t>(text.size());
    const int32_t sizing = llama_tokenize(vocab, text.data(), length, nullptr, 0, add_special, true);
    // With no output capacity, llama.cpp returns NEGATIVE required capacity.
    // INT32_MIN indicates overflow and cannot safely be negated.
    if (sizing >= 0 || sizing == std::numeric_limits<int32_t>::min()) {
        throw std::runtime_error("prompt tokenization returned no valid token count");
    }
    std::vector<llama_token> tokens(static_cast<size_t>(-sizing));
    const int32_t written = llama_tokenize(
        vocab, text.data(), length, tokens.data(), -sizing, add_special, true);
    if (written <= 0 || written > -sizing) {
        throw std::runtime_error("prompt tokenization failed on second pass");
    }
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

inline std::string tokenPiece(const llama_vocab* vocab, llama_token token) {
    std::vector<char> buffer(64);
    int32_t written = llama_token_to_piece(vocab, token, buffer.data(), 64, 0, true);
    if (written < 0) {
        constexpr int32_t maxPieceBytes = 65536;
        if (written < -maxPieceBytes) throw std::runtime_error("token piece exceeds byte budget");
        buffer.resize(static_cast<size_t>(-written));
        written = llama_token_to_piece(
            vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true);
    }
    if (written < 0 || static_cast<size_t>(written) > buffer.size()) {
        throw std::runtime_error("token_to_piece returned an invalid length");
    }
    return std::string(buffer.data(), static_cast<size_t>(written));
}

}  // namespace hymt
