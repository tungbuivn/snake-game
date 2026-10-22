package com.dsh.snake;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Tiny synthesized sound effects for the game.
 *
 * No audio assets are shipped: each effect is generated as a short 16-bit mono
 * PCM buffer, written to the app cache as a WAV and loaded into a SoundPool. The
 * pool allows effects to overlap (eating while the fruit popup is still alive,
 * for example) and keeps playback off the render thread.
 */
final class SoundFx {

    private static final int RATE = 22050;

    private final SoundPool pool;
    private final int eat;
    private final int die;
    private final int win;
    private final int tap;
    private final int move;
    private final int auto;
    private final int error;
    private final int bite;
    private final int start;
    private boolean released;

    SoundFx(Context context) {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build();
        eat = load(context, "sfx_eat.wav", eatSound());
        die = load(context, "sfx_die.wav", dieSound());
        win = load(context, "sfx_win.wav", winSound());
        tap = load(context, "sfx_tap.wav", tapSound());
        move = load(context, "sfx_move.wav", moveSound());
        auto = load(context, "sfx_auto.wav", autoSound());
        error = load(context, "sfx_error.wav", errorSound());
        bite = load(context, "sfx_bite.wav", biteSound());
        start = load(context, "sfx_start.wav", startSound());
    }

    private int load(Context context, String name, short[] pcm) {
        try {
            File f = new File(context.getCacheDir(), name);
            writeWav(f, pcm);
            return pool.load(f.getAbsolutePath(), 1);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void play(int id, float volume) {
        if (released || id == 0) {
            return;
        }
        try {
            pool.play(id, volume, volume, 1, 0, 1f);
        } catch (Throwable ignored) {
            // audio is cosmetic: never let it break the game
        }
    }

    void playEat() {
        play(eat, 0.85f);
    }

    void playDie() {
        play(die, 0.90f);
    }

    void playWin() {
        play(win, 0.90f);
    }

    void playTap() {
        play(tap, 0.60f);
    }

    void playMove() {
        play(move, 0.70f);
    }

    void playAuto() {
        play(auto, 0.75f);
    }

    void playError() {
        play(error, 0.75f);
    }

    void playBite() {
        play(bite, 0.85f);
    }

    void playStart() {
        play(start, 0.75f);
    }

    void release() {
        released = true;
        try {
            pool.release();
        } catch (Throwable ignored) {
            // nothing to do
        }
    }

    /* ------------------------------------------------------------------ */
    /* Synthesis                                                          */
    /* ------------------------------------------------------------------ */

    /** A short note that glides from {@code hz0} to {@code hz1}. */
    private static short[] slide(double hz0, double hz1, int ms, double volume) {
        int n = Math.max(1, RATE * ms / 1000);
        short[] out = new short[n];
        double phase = 0.0;
        int attack = Math.max(1, RATE / 250);          // ~4 ms
        for (int i = 0; i < n; i++) {
            double t = (double) i / n;
            double hz = hz0 + (hz1 - hz0) * t;
            phase += 2.0 * Math.PI * hz / RATE;
            double env = Math.min(1.0, (double) i / attack) * Math.pow(1.0 - t, 1.35);
            double s = Math.sin(phase) * env * volume;
            out[i] = clamp(s);
        }
        return out;
    }

    private static short[] tone(double hz, int ms, double volume) {
        return slide(hz, hz, ms, volume);
    }

    /** A melody of notes, each followed by a short silence. */
    private static short[] sequence(double[] hz, int noteMs, int gapMs, double volume) {
        int noteN = Math.max(1, RATE * noteMs / 1000);
        int gapN = Math.max(0, RATE * gapMs / 1000);
        int total = hz.length * noteN + Math.max(0, hz.length - 1) * gapN;
        short[] out = new short[total];
        int off = 0;
        for (int i = 0; i < hz.length; i++) {
            short[] note = tone(hz[i], noteMs, volume);
            int len = Math.min(note.length, total - off);
            System.arraycopy(note, 0, out, off, len);
            off += noteN;
            if (i < hz.length - 1) {
                off += gapN;
            }
        }
        return out;
    }

    private static short[] noise(int ms, double volume) {
        int n = Math.max(1, RATE * ms / 1000);
        short[] out = new short[n];
        Random rnd = new Random(7);
        for (int i = 0; i < n; i++) {
            double t = (double) i / n;
            double env = Math.pow(1.0 - t, 2.0);
            double s = (rnd.nextDouble() * 2.0 - 1.0) * env * volume;
            out[i] = clamp(s);
        }
        return out;
    }

    private static short[] mix(short[] a, short[] b) {
        int n = Math.max(a.length, b.length);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            int va = i < a.length ? a[i] : 0;
            int vb = i < b.length ? b[i] : 0;
            out[i] = clamp((va + vb) / 32768.0);
        }
        return out;
    }

    private static short clamp(double s) {
        double v = s * 32767.0;
        if (v > 32767.0) {
            v = 32767.0;
        } else if (v < -32768.0) {
            v = -32768.0;
        }
        return (short) v;
    }

    /* ------------------------------------------------------------------ */
    /* WAV writer (16-bit PCM, mono)                                      */
    /* ------------------------------------------------------------------ */

    private static void writeWav(File file, short[] pcm) throws IOException {
        int dataLen = pcm.length * 2;
        byte[] header = new byte[44];
        putInt(header, 0, 0x46464952);          // "RIFF"
        putInt(header, 4, 36 + dataLen);
        putInt(header, 8, 0x45564157);          // "WAVE"
        putInt(header, 12, 0x20746D66);         // "fmt "
        putInt(header, 16, 16);                 // fmt chunk size
        putShort(header, 20, 1);                // PCM
        putShort(header, 22, 1);                // mono
        putInt(header, 24, RATE);
        putInt(header, 28, RATE * 2);           // byte rate
        putShort(header, 32, 2);                // block align
        putShort(header, 34, 16);               // bits per sample
        putInt(header, 36, 0x61746164);         // "data"
        putInt(header, 40, dataLen);

        byte[] data = new byte[dataLen];
        for (int i = 0; i < pcm.length; i++) {
            data[i * 2] = (byte) (pcm[i] & 0xFF);
            data[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(header);
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    /* ------------------------------------------------------------------ */
    /* Effect definitions                                                 */
    /* ------------------------------------------------------------------ */

    private static short[] eatSound() {
        return sequence(new double[]{880.0, 1174.7, 1568.0}, 45, 8, 0.38);
    }

    private static short[] dieSound() {
        return slide(520.0, 70.0, 520, 0.45);
    }

    private static short[] winSound() {
        return sequence(new double[]{523.25, 659.25, 783.99, 1046.5}, 70, 12, 0.42);
    }

    private static short[] tapSound() {
        return sequence(new double[]{1500.0, 1000.0}, 18, 4, 0.20);
    }

    private static short[] moveSound() {
        return slide(320.0, 760.0, 140, 0.32);
    }

    private static short[] autoSound() {
        return sequence(new double[]{659.25, 880.0, 1174.7}, 55, 10, 0.34);
    }

    private static short[] errorSound() {
        return sequence(new double[]{220.0, 165.0}, 110, 15, 0.38);
    }

    private static short[] biteSound() {
        return mix(noise(90, 0.50), slide(180.0, 100.0, 90, 0.35));
    }

    private static short[] startSound() {
        return sequence(new double[]{392.0, 523.25, 659.25}, 55, 8, 0.35);
    }
}
