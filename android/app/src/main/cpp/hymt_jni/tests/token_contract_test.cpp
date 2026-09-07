// Unit tests of the production adapter using the real pinned API declarations.
// These substitutes test buffer contracts; they do not perform model inference.
#include "hymt_tokens.h"
#include <cassert>
#include <cstring>
#include <iostream>
#include <limits>

namespace {
enum class TokenizerMode { normal, overflow, secondFailure, invalidCount };
TokenizerMode mode = TokenizerMode::normal;
std::string piece;
bool oversizedPiece = false;
int pieceCalls = 0;

template<class Operation>
void expectFailure(Operation operation) {
    bool failed = false;
    try { operation(); } catch (const std::runtime_error&) { failed = true; }
    assert(failed);
}
}

extern "C" int32_t llama_tokenize(
    const llama_vocab*, const char*, int32_t, llama_token* tokens,
    int32_t capacity, bool, bool) {
    if (mode == TokenizerMode::overflow) return std::numeric_limits<int32_t>::min();
    if (capacity == 0) return -3;
    assert(capacity == 3);
    if (mode == TokenizerMode::secondFailure) return -3;
    if (mode == TokenizerMode::invalidCount) return 4;
    tokens[0] = 11; tokens[1] = 22; tokens[2] = 33;
    return 3;
}

extern "C" int32_t llama_token_to_piece(
    const llama_vocab*, llama_token, char* buffer, int32_t capacity, int32_t, bool) {
    ++pieceCalls;
    if (oversizedPiece) return -65537;
    if (static_cast<size_t>(capacity) < piece.size()) return -static_cast<int32_t>(piece.size());
    std::memcpy(buffer, piece.data(), piece.size());
    return static_cast<int32_t>(piece.size());
}

int main() {
    assert((hymt::tokenize(nullptr, "translate") == std::vector<llama_token>{11, 22, 33}));
    mode = TokenizerMode::overflow;
    expectFailure([] { hymt::tokenize(nullptr, "translate"); });
    mode = TokenizerMode::secondFailure;
    expectFailure([] { hymt::tokenize(nullptr, "translate"); });
    mode = TokenizerMode::invalidCount;
    expectFailure([] { hymt::tokenize(nullptr, "translate"); });
    piece = u8"𠀀🙂粤语";
    assert(hymt::tokenPiece(nullptr, 1) == piece);
    piece = std::string(1000, 'x');
    pieceCalls = 0;
    assert(hymt::tokenPiece(nullptr, 1) == piece);
    assert(pieceCalls == 2);
    oversizedPiece = true;
    expectFailure([] { hymt::tokenPiece(nullptr, 1); });
    std::cout << "7 token adapter contract cases passed\n";
}
