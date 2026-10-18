package net.yumezawa.queqiao_map_web.model;

/**
 * 瓦片载荷：cx/cz 为批量区域左上角【区块坐标】，size 为每边区块数（1 或 8）。
 * format=zlib-pal8：调色板下标（1 字节/像素，下标 0=未设置）按行优先铺成
 * (size*16)^2 字节后 zlib 压缩；sha1 为压缩字节的小写十六进制，供后端去重。
 */
public final class TilePayloadModel {

    private final int cx;
    private final int cz;
    private final int size;
    private final String format;
    private final String sha1;
    private final String b64;

    public TilePayloadModel(int cx, int cz, int size, String format, String sha1, String b64) {
        this.cx = cx;
        this.cz = cz;
        this.size = size;
        this.format = format;
        this.sha1 = sha1;
        this.b64 = b64;
    }

    public int getCx() {
        return cx;
    }

    public int getCz() {
        return cz;
    }

    public int getSize() {
        return size;
    }

    public String getFormat() {
        return format;
    }

    public String getSha1() {
        return sha1;
    }

    public String getB64() {
        return b64;
    }
}
