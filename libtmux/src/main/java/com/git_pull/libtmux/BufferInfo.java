package com.git_pull.libtmux;

/**
 * One tmux paste buffer, as a listing described it.
 *
 * @param name the buffer name, which is how every other buffer command addresses it
 * @param size how many bytes it holds
 */
public record BufferInfo(String name, int size) {}
