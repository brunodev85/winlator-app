package com.winlator.win32;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.winlator.core.ImageUtils;
import com.winlator.core.StreamUtils;
import com.winlator.core.StringUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Stack;

public class PEParser {
    private static final byte RT_ICON = 3;
    private static final byte RT_VERSION = 16;
    private final File peFile;
    private int resourcesRVA = 0;
    private int resourcesOffset = 0;

    public static class FileVersionInfo {
        public String Comments = "";
        public String CompanyName = "";
        public String FileDescription = "";
        public String FileVersion = "";
        public String InternalName = "";
        public String LegalCopyright = "";
        public String LegalTrademarks = "";
        public String OriginalFilename = "";
        public String PrivateBuild = "";
        public String ProductName = "";
        public String ProductVersion = "";
        public String SpecialBuildprivate = "";
    }

    private interface ImageResourceEntry {}

    private static class ImageResourceDirectoryEntry implements ImageResourceEntry {
        private final int name;
        private final boolean nameIsString;
        private final int offsetToData;
        private final boolean dataIsDirectory;
        private ImageResourceDirectory directory;

        private ImageResourceDirectoryEntry(ByteBuffer data) {
            int field1 = data.getInt();
            int field2 = data.getInt();

            this.name = field1 & 0x7fffffff;
            this.nameIsString = ((field1 >> 31) & 0x1) != 0;
            this.offsetToData = field2 & 0x7fffffff;
            this.dataIsDirectory = ((field2 >> 31) & 0x1) != 0;
        }
    }

    private static class ImageResourceDataEntry implements ImageResourceEntry {
        private final int offsetToData;
        private final int size;
        private final int codePage;
        private final int reserved;

        private ImageResourceDataEntry(ByteBuffer data) {
            this.offsetToData = data.getInt();
            this.size = data.getInt();
            this.codePage = data.getInt();
            this.reserved = data.getInt();
        }
    }

    private static class ImageResourceDirectory {
        private final int characteristics;
        private final int timeDateStamp;
        private final short majorVersion;
        private final short minorVersion;
        private final short numberOfNamedEntries;
        private final short numberOfIdEntries;
        private final ArrayList<ImageResourceEntry> entries = new ArrayList<>();

        private ImageResourceDirectory(byte type, ByteBuffer data, int level) {
            characteristics = data.getInt();
            timeDateStamp = data.getInt();
            majorVersion = data.getShort();
            minorVersion = data.getShort();
            numberOfNamedEntries = data.getShort();
            numberOfIdEntries = data.getShort();

            int numberOfEntries = numberOfNamedEntries + numberOfIdEntries;
            for (int i = 0; i < numberOfEntries; i++) {
                ImageResourceDirectoryEntry directoryEntry = new ImageResourceDirectoryEntry(data);

                if ((directoryEntry.name == type && directoryEntry.dataIsDirectory) || (level > 0 && directoryEntry.dataIsDirectory)) {
                    int oldPosition = data.position();
                    data.position(directoryEntry.offsetToData);
                    directoryEntry.directory = new ImageResourceDirectory(type, data, level + 1);
                    data.position(oldPosition);

                    entries.add(0, directoryEntry);
                }
                else if (level > 0) {
                    int oldPosition = data.position();
                    data.position(directoryEntry.offsetToData);
                    ImageResourceDataEntry dataEntry = new ImageResourceDataEntry(data);
                    data.position(oldPosition);

                    entries.add(0, dataEntry);
                }
            }
        }
    }

    private static class VSFixedFileInfo {
        private final int dwSignature;
        private final int dwStrucVersion;
        private final int dwFileVersionMS;
        private final int dwFileVersionLS;
        private final int dwProductVersionMS;
        private final int dwProductVersionLS;
        private final int dwFileFlagsMask;
        private final int dwFileFlags;
        private final int dwFileOS;
        private final int dwFileType;
        private final int dwFileSubtype;
        private final int dwFileDateMS;
        private final int dwFileDateLS;

        private VSFixedFileInfo(ByteBuffer data) {
            dwSignature = data.getInt();
            dwStrucVersion = data.getInt();
            dwFileVersionMS = data.getInt();
            dwFileVersionLS = data.getInt();
            dwProductVersionMS = data.getInt();
            dwProductVersionLS = data.getInt();
            dwFileFlagsMask = data.getInt();
            dwFileFlags = data.getInt();
            dwFileOS = data.getInt();
            dwFileType = data.getInt();
            dwFileSubtype = data.getInt();
            dwFileDateMS = data.getInt();
            dwFileDateLS = data.getInt();
        }
    }

    private static String readUnicodeString(ByteBuffer data) {
        ByteBuffer stringBuf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        short value;
        while (data.remaining() >= 2 && (value = data.getShort()) != 0) {
            if (stringBuf.hasRemaining()) stringBuf.putShort(value);
        }
        return new String(Arrays.copyOf(stringBuf.array(), stringBuf.position()), StandardCharsets.UTF_16LE);
    }

    private static class StringHdr {
        private final short length;
        private final short valueLength;
        private final short type;
        private final String key;
        private final String value;

        private StringHdr(ByteBuffer data) {
            if (data.remaining() < 6) {
                length = 0; valueLength = 0; type = 0; key = ""; value = null;
                return;
            }
            int position = data.position();
            length = data.getShort();
            valueLength = data.getShort();
            type = data.getShort();

            key = readUnicodeString(data);
            int offset = data.position() - position;
            if ((offset & 3) != 0 && data.remaining() >= 2) data.getShort();

            if (valueLength > 0 && data.remaining() >= valueLength * 2) {
                byte[] bytes = new byte[valueLength * 2];
                data.get(bytes, 0, bytes.length);
                value = readUnicodeString(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
            }
            else value = null;
            if ((length & 3) != 0 && data.remaining() >= 2) data.getShort();
        }
    }

    private static class StringTable {
        private final short length;
        private final short valueLength;
        private final short type;
        private final String key;
        private final ArrayList<StringHdr> stringHdrs = new ArrayList<>();

        private StringTable(ByteBuffer data) {
            if (data.remaining() < 6) {
                length = 0; valueLength = 0; type = 0; key = "";
                return;
            }
            int position = data.position();
            length = data.getShort();
            valueLength = data.getShort();
            type = data.getShort();
            key = readUnicodeString(data);
            int offset = data.position() - position;
            if ((offset & 3) != 0 && data.remaining() >= 2) data.getShort();
            int remaining = length - offset;

            while (remaining > 0 && data.remaining() >= 6) {
                StringHdr stringhdr = new StringHdr(data);
                if (stringhdr.length <= 0) break;
                stringHdrs.add(stringhdr);
                remaining -= stringhdr.length;
            }
            if ((length & 3) != 0 && data.remaining() >= 2) data.getShort();
        }
    }

    private static class StringFileInfo {
        private final short length;
        private final short valueLength;
        private final short type;
        private final String key;
        private final ArrayList<StringTable> stringTables = new ArrayList<>();

        private StringFileInfo(ByteBuffer data) {
            if (data.remaining() < 6) {
                length = 0; valueLength = 0; type = 0; key = "";
                return;
            }
            int position = data.position();
            length = data.getShort();
            valueLength = data.getShort();
            type = data.getShort();
            key = readUnicodeString(data);
            if (!key.equals("StringFileInfo")) return;
            int offset = data.position() - position;
            if ((offset & 3) != 0 && data.remaining() >= 2) data.getShort();
            int remaining = length - offset;

            while (remaining > 0 && data.remaining() >= 6) {
                StringTable stringTable = new StringTable(data);
                if (stringTable.length <= 0) break;
                stringTables.add(stringTable);
                remaining -= stringTable.length;
            }
            if ((length & 3) != 0 && data.remaining() >= 2) data.getShort();
        }
    }

    private static class VSVersionInfo {
        private final short length;
        private final short valueLength;
        private final short type;
        private final String key;
        private final VSFixedFileInfo value;
        private final StringFileInfo stringFileInfo;

        private VSVersionInfo(ByteBuffer data) {
            if (data.remaining() < 6) {
                length = 0; valueLength = 0; type = 0; key = ""; value = null; stringFileInfo = null;
                return;
            }
            int position = data.position();
            length = data.getShort();
            valueLength = data.getShort();
            type = data.getShort();
            key = readUnicodeString(data);
            int offset = data.position() - position;
            if ((offset & 3) != 0 && data.remaining() >= 2) data.getShort();
            value = (valueLength > 0 && data.remaining() >= 52) ? new VSFixedFileInfo(data) : null;

            if (value == null || value.dwStrucVersion != 0x10000) {
                stringFileInfo = null;
                return;
            }

            stringFileInfo = data.remaining() >= 6 ? new StringFileInfo(data) : null;
        }
    }

    private PEParser(File peFile) {
        this.peFile = peFile;
    }

    private ByteBuffer readResourceData(int dataOffset, int dataSize) {
        try (InputStream inStream = new BufferedInputStream(new FileInputStream(peFile), StreamUtils.BUFFER_SIZE)) {
            byte[] bytes = new byte[dataSize];
            StreamUtils.skip(inStream, dataOffset);
            int bytesRead = inStream.read(bytes);

            return bytesRead != -1 ? ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN) : null;
        }
        catch (IOException e) {
            return null;
        }
    }

    private ImageResourceDirectory readImageResourceDirectory(byte type) {
        try (InputStream inStream = new BufferedInputStream(new FileInputStream(peFile), StreamUtils.BUFFER_SIZE)) {
            int filePosition = 0;

            ByteBuffer dosHeader = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            filePosition += inStream.read(dosHeader.array());

            short magicNumber = dosHeader.getShort();
            if (magicNumber != 0x5a4d) return null;

            dosHeader.position(60);
            int fileHeaderOffset = dosHeader.getInt() + 4;

            filePosition += StreamUtils.skip(inStream, fileHeaderOffset - filePosition);

            ByteBuffer fileHeader = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            filePosition += inStream.read(fileHeader.array());

            int machine = Short.toUnsignedInt(fileHeader.getShort());
            short numberOfSections = fileHeader.getShort();

            fileHeader.position(fileHeader.position() + 12);
            short sizeofOptionalHeader = fileHeader.getShort();

            filePosition += StreamUtils.skip(inStream, sizeofOptionalHeader);

            resourcesRVA = 0;
            resourcesOffset = 0;
            int resourcesSize = 0;

            ByteBuffer sectionHeader = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
            byte[] nameBytes = new byte[8];

            for (byte i = 0; i < numberOfSections; i++) {
                sectionHeader.position(0);
                filePosition += inStream.read(sectionHeader.array());

                sectionHeader.get(nameBytes);
                String name = StringUtils.fromANSIString(nameBytes);

                if (name.equals(".rsrc")) {
                    sectionHeader.getInt();
                    resourcesRVA = sectionHeader.getInt();
                    resourcesSize = sectionHeader.getInt();
                    resourcesOffset = sectionHeader.getInt();
                    break;
                }
            }

            if (resourcesOffset > 0) {
                filePosition += StreamUtils.skip(inStream, resourcesOffset - filePosition);

                ByteBuffer resourcesBuffer = ByteBuffer.allocate(resourcesSize).order(ByteOrder.LITTLE_ENDIAN);
                inStream.read(resourcesBuffer.array(), 0, resourcesBuffer.limit());

                return new ImageResourceDirectory(type, resourcesBuffer, 0);
            }

            return null;
        }
        catch (IOException e) {
            return null;
        }
    }

    private ArrayList<ImageResourceDataEntry> readImageResourceDataEntries(ImageResourceDirectory rootDirectory) {
        ArrayList<ImageResourceDataEntry> dataEntries = new ArrayList<>();
        Stack<ImageResourceDirectory> stack = new Stack<>();
        stack.push(rootDirectory);
        while (!stack.isEmpty()) {
            ImageResourceDirectory directory = stack.pop();

            for (ImageResourceEntry entry : directory.entries) {
                if (entry instanceof ImageResourceDirectoryEntry) {
                    stack.push(((ImageResourceDirectoryEntry)entry).directory);
                }
                else if (entry instanceof ImageResourceDataEntry) {
                    dataEntries.add((ImageResourceDataEntry)entry);
                }
            }
        }
        return dataEntries;
    }

    private Bitmap decodeIcon(int iconIndex, boolean largeIcon, ArrayList<ImageResourceDataEntry> dataEntries) {
        for (int i = 0; i < dataEntries.size(); i++) {
            ImageResourceDataEntry dataEntry = dataEntries.get(i);
            int fileOffset = dataEntry.offsetToData - resourcesRVA + resourcesOffset;

            ByteBuffer iconData = readResourceData(fileOffset, dataEntry.size);
            if (iconData != null) {
                if (ImageUtils.isPNGData(iconData)) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(iconData.array(), 0, iconData.limit(), options);

                    boolean success = iconIndex >= 0 ? i == iconIndex : (largeIcon == (options.outWidth >= 32));
                    if (success) return BitmapFactory.decodeByteArray(iconData.array(), 0, iconData.limit());
                }
                else {
                    int bitmapOffset = iconData.getInt();
                    int bmpWidth = iconData.getInt();
                    int bmpHeight = iconData.getInt();
                    short colorPlanes = iconData.getShort();
                    short bitCount = iconData.getShort();
                    int compression = iconData.getInt();
                    int sizeImage = iconData.getInt();
                    int xPelsPerMeter = iconData.getInt();
                    int yPelsPerMeter = iconData.getInt();
                    int clrUsed = iconData.getInt();

                    if (bitCount == 8 && (compression != 0 || clrUsed != 0)) continue;

                    boolean success = (iconIndex >= 0 ? i == iconIndex : (largeIcon == (bmpWidth >= 32))) && bitCount >= 8;
                    if (success) {
                        iconData.position(bitmapOffset);
                        Bitmap bitmap = MSBitmap.decodeBuffer(bmpWidth, bmpWidth, bitCount, iconData);
                        if (bitmap != null) return bitmap;
                    }
                }
            }
        }

        return null;
    }

    private Bitmap extractIcon(int iconIndex) {
        if (!peFile.isFile()) return null;

        ImageResourceDirectory rootDirectory = readImageResourceDirectory(RT_ICON);
        if (rootDirectory == null) return null;
        ArrayList<ImageResourceDataEntry> dataEntries = readImageResourceDataEntries(rootDirectory);

        if (iconIndex >= 0) {
            return decodeIcon(iconIndex, true, dataEntries);
        }
        else {
            Bitmap bitmap = decodeIcon(-1, true, dataEntries);
            if (bitmap != null) return bitmap;

            bitmap = decodeIcon(-1, false, dataEntries);
            if (bitmap != null) return bitmap;
        }

        return null;
    }

    public static FileVersionInfo getFileVersionInfo(File peFile) {
        if (!peFile.isFile()) return null;

        try {
            PEParser peParser = new PEParser(peFile);
            ImageResourceDirectory rootDirectory = peParser.readImageResourceDirectory(RT_VERSION);
            if (rootDirectory == null) return null;
            ArrayList<ImageResourceDataEntry> dataEntries = peParser.readImageResourceDataEntries(rootDirectory);
            if (dataEntries.size() != 1) return null;

            ImageResourceDataEntry dataEntry = dataEntries.get(0);
            int fileOffset = dataEntry.offsetToData - peParser.resourcesRVA + peParser.resourcesOffset;
            ByteBuffer resourceData = peParser.readResourceData(fileOffset, dataEntry.size);
            if (resourceData == null) return null;

            VSVersionInfo versionInfo = new VSVersionInfo(resourceData);

            if (versionInfo.stringFileInfo != null) {
                final String englishLangID = "0409";
                FileVersionInfo fileVersionInfo = new FileVersionInfo();
                for (StringTable stringTable : versionInfo.stringFileInfo.stringTables) {
                    if (stringTable.key.startsWith(englishLangID) || stringTable.key.startsWith("0000") || stringTable.key.startsWith("ffff")) {
                        for (StringHdr stringHdr : stringTable.stringHdrs) {
                            if (stringHdr.key == null) continue;
                            String stringHdrValue = stringHdr.value != null ? stringHdr.value : "";
                            switch (stringHdr.key) {
                                case "Comments":
                                    fileVersionInfo.Comments = stringHdrValue;
                                    break;
                                case "CompanyName":
                                    fileVersionInfo.CompanyName = stringHdrValue;
                                    break;
                                case "FileDescription":
                                    fileVersionInfo.FileDescription = stringHdrValue;
                                    break;
                                case "FileVersion":
                                    fileVersionInfo.FileVersion = stringHdrValue;
                                    break;
                                case "InternalName":
                                    fileVersionInfo.InternalName = stringHdrValue;
                                    break;
                                case "LegalCopyright":
                                    fileVersionInfo.LegalCopyright = stringHdrValue;
                                    break;
                                case "LegalTrademarks":
                                    fileVersionInfo.LegalTrademarks = stringHdrValue;
                                    break;
                                case "OriginalFilename":
                                    fileVersionInfo.OriginalFilename = stringHdrValue;
                                    break;
                                case "PrivateBuild":
                                    fileVersionInfo.PrivateBuild = stringHdrValue;
                                    break;
                                case "ProductName":
                                    fileVersionInfo.ProductName = stringHdrValue;
                                    break;
                                case "ProductVersion":
                                    fileVersionInfo.ProductVersion = stringHdrValue;
                                    break;
                                case "SpecialBuildprivate":
                                    fileVersionInfo.SpecialBuildprivate = stringHdrValue;
                                    break;
                            }
                        }
                    }
                }
                return fileVersionInfo;
            }
        }
        catch (Exception e) {
            return null;
        }
        return null;
    }

    public static Bitmap extractIcon(File peFile) {
        return extractIcon(peFile, -1);
    }

    public static Bitmap extractIcon(File peFile, int iconIndex) {
        return (new PEParser(peFile)).extractIcon(iconIndex);
    }
}
