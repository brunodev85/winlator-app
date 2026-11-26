package com.winlator.win32;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.winlator.core.FileUtils;
import com.winlator.core.ImageUtils;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;

public class MSIcon {
    private static class IconDirEntry {
        private int width;
        private int height;
        private byte numberOfColors;
        private byte reserved;
        private short colorPlanes;
        private short bitCount;
        private int imageSize;
        private int imageOffset;
    }

    public static Bitmap decodeByteArray(byte[] bytes, int offset, int length) {
        if (offset < 0 || length <= 0 || offset + length > bytes.length) return null;

        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        data.position(offset);

        if (ImageUtils.isPNGData(data)) {
            return BitmapFactory.decodeByteArray(bytes, offset, length);
        }
        else {
            int bitmapOffset = data.getInt();
            int bmpWidth = data.getInt();
            int bmpHeight = data.getInt();
            short colorPlanes = data.getShort();
            short bitCount = data.getShort();

            // bitmapOffset 是 BITMAPINFOHEADER 大小，调色板/像素数据从其后开始；
            // ico 内的 bmpHeight 是图像高度的两倍（含 AND 掩码）
            int bmpHeightHalf = Math.abs(bmpHeight) / 2;
            if (bmpWidth <= 0 || bitmapOffset < 16 || bitmapOffset > length) return null;

            data.position(offset + bitmapOffset);
            return MSBitmap.decodeBuffer(bmpWidth, bmpHeightHalf > 0 ? bmpHeightHalf : bmpWidth, bitCount, data);
        }
    }

    public static Bitmap decodeFile(File icoFile) {
        if (!icoFile.isFile()) return null;
        byte[] bytes = FileUtils.read(icoFile);
        if (bytes == null) return null;

        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        short reserved = data.getShort();
        short imageType = data.getShort();
        short numberOfImages = data.getShort();

        if (reserved != 0 || imageType != 1 || (numberOfImages <= 0 || numberOfImages >= 32)) return null;
        if (bytes.length < 6 + numberOfImages * 16) return null;

        ArrayList<IconDirEntry> entries = new ArrayList<>();

        for (byte i = 0; i < numberOfImages; i++) {
            IconDirEntry entry = new IconDirEntry();
            entry.width = Byte.toUnsignedInt(data.get());
            entry.height = Byte.toUnsignedInt(data.get());
            entry.numberOfColors = data.get();
            entry.reserved = data.get();
            entry.colorPlanes = data.getShort();
            entry.bitCount = data.getShort();
            entry.imageSize = data.getInt();
            entry.imageOffset = data.getInt();
            entries.add(entry);
        }

        Collections.sort(entries, (a, b) -> {
            int value = Short.compare(b.bitCount, a.bitCount);
            if (value != 0) return value;
            return Integer.compare(b.width, a.width);
        });

        IconDirEntry firstEntry = entries.get(0);
        if (firstEntry.imageOffset <= 0 || firstEntry.imageSize <= 0 ||
            firstEntry.imageOffset + firstEntry.imageSize > bytes.length) return null;
        return decodeByteArray(data.array(), firstEntry.imageOffset, firstEntry.imageSize);
    }
}
