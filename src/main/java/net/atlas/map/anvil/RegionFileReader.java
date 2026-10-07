// src/main/java/net/atlas/map/anvil/RegionFileReader.java
package net.atlas.map.anvil;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.Semaphore;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public final class RegionFileReader implements AutoCloseable {

    private final File regionFile;
    private final Semaphore ioLimiter;
    private RandomAccessFile raf;
    private FileChannel channel;

    public RegionFileReader(File regionFile, Semaphore ioLimiter) {
        this.regionFile = regionFile;
        this.ioLimiter = ioLimiter;
    }

    private synchronized FileChannel getOrCreateChannel() throws IOException {
        if (channel == null || !channel.isOpen()) {
            if (!regionFile.exists() || regionFile.length() < 8192) {
                return null;
            }
            this.raf = new RandomAccessFile(regionFile, "r");
            this.channel = raf.getChannel();
        }
        return channel;
    }

    public CompoundBinaryTag readChunkNbt(int chunkXInRegion, int chunkZInRegion) throws IOException {
        int chunkIndex = (chunkXInRegion & 31) + (chunkZInRegion & 31) * 32;
        int headerOffset = chunkIndex * 4;

        boolean acquired = false;
        try {
            if (ioLimiter != null) {
                ioLimiter.acquire();
                acquired = true;
            }

            FileChannel ch = getOrCreateChannel();
            if (ch == null || !ch.isOpen()) {
                return null;
            }

            ByteBuffer locationBuffer = ByteBuffer.allocate(4);
            int bytesRead = ch.read(locationBuffer, headerOffset);
            if (bytesRead < 4) {
                return null;
            }
            locationBuffer.flip();

            int b0 = locationBuffer.get() & 0xFF;
            int b1 = locationBuffer.get() & 0xFF;
            int b2 = locationBuffer.get() & 0xFF;
            int sectorCount = locationBuffer.get() & 0xFF;

            int sectorOffset = (b0 << 16) | (b1 << 8) | b2;
            if (sectorOffset == 0 || sectorCount == 0) {
                return null; // Chunk does not exist in this region
            }

            long chunkByteOffset = (long) sectorOffset * 4096L;
            long chSize = ch.size();
            if (chunkByteOffset + 5 > chSize) {
                return null;
            }

            ByteBuffer metaBuffer = ByteBuffer.allocate(5);
            long curPos = chunkByteOffset;
            while (metaBuffer.hasRemaining()) {
                int r = ch.read(metaBuffer, curPos);
                if (r < 0) return null;
                curPos += r;
            }
            metaBuffer.flip();

            int length = metaBuffer.getInt();
            byte compressionType = metaBuffer.get();

            if (length <= 1 || (chunkByteOffset + 4 + length) > chSize) {
                return null;
            }

            int dataLength = length - 1;
            ByteBuffer dataBuffer = ByteBuffer.allocate(dataLength);
            curPos = chunkByteOffset + 5;
            while (dataBuffer.hasRemaining()) {
                int r = ch.read(dataBuffer, curPos);
                if (r < 0) return null;
                curPos += r;
            }
            byte[] rawData = dataBuffer.array();

            ByteArrayInputStream byteStream = new ByteArrayInputStream(rawData);
            if (compressionType == 1) { // GZIP
                try (GZIPInputStream gz = new GZIPInputStream(byteStream)) {
                    return BinaryTagIO.unlimitedReader().read(gz);
                }
            } else if (compressionType == 2) { // ZLIB / Deflate
                try (InflaterInputStream inf = new InflaterInputStream(byteStream)) {
                    return BinaryTagIO.unlimitedReader().read(inf);
                }
            } else if (compressionType == 3) { // Uncompressed
                return BinaryTagIO.unlimitedReader().read(byteStream);
            } else {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Chunk read interrupted while waiting for I/O semaphore", e);
        } finally {
            if (acquired && ioLimiter != null) {
                ioLimiter.release();
            }
        }
    }

    @Override
    public synchronized void close() {
        if (channel != null) {
            try { channel.close(); } catch (Throwable ignored) {}
            channel = null;
        }
        if (raf != null) {
            try { raf.close(); } catch (Throwable ignored) {}
            raf = null;
        }
    }
}
