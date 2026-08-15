package io.github.libtmux.docs;

import java.nio.file.Path;

/**
 * One fenced Java block, and what the documentation claims about it.
 *
 * @param file the document it came from, relative to the repository root
 * @param line the line the fence opened on, so a failure names somewhere to look
 * @param expectation what compiling it is supposed to prove
 * @param shape whether the block is a set of statements or a whole type
 * @param code the fence's contents
 */
record Snippet(Path file, int line, Expectation expectation, String detail, Shape shape, String code) {

    /** What the document says about a block, and therefore what the build must check. */
    enum Expectation {
        /**
         * The default: it must compile <em>and</em> run against a real tmux server.
         *
         * <p>Compiling proves the API has the shape the document describes. Running proves the
         * document is right about what happens, which is what a reader depends on and what a
         * compiler cannot check.
         */
        RUNS,

        /**
         * Must compile, but is not run, and the directive has to say why.
         *
         * <p>For the snippets that would end the fixture, block on a protocol stream, or name a
         * type belonging to the reader rather than to this library.
         */
        COMPILES,

        /**
         * Must <em>not</em> compile.
         *
         * <p>For the blocks that exist to show a mistake the compiler catches. Left unchecked, a
         * claim that {@code Pane_.index().startsWith("2")} is rejected would survive the day it
         * stopped being true, which is the one thing that claim cannot afford.
         */
        DOES_NOT_COMPILE,

        /**
         * Must run and must fail, with the exception the directive names.
         *
         * <p>For the snippets whose whole point is that something is refused. A document saying
         * "this is rejected" is a claim like any other, and this is what turns it into one the
         * build keeps.
         */
        THROWS,

        /** Not checked, and the directive has to say why. */
        SKIPPED
    }

    /** How a block has to be wrapped before javac will look at it. */
    enum Shape {
        /** Statements, wrapped in a method body. */
        STATEMENTS,

        /** A whole type already: a class, record, interface or enum. */
        TYPE
    }

    /** Where a failure should send someone, in the form an editor and a terminal both understand. */
    String where() {
        return file + ":" + line;
    }

    @Override
    public String toString() {
        return where();
    }
}
