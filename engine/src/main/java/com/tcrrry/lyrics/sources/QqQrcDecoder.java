package com.tcrrry.lyrics.sources;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.InflaterInputStream;

/**
 * Decoder for QQ Music cloud QRC payloads.
 *
 * The non-standard DES tables and QRC key are derived from QQMusicDecoder:
 * Copyright (c) 2023 WXRIW, licensed under the MIT License.
 * Permission is granted to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies, provided this notice is retained.
 */
public final class QqQrcDecoder {
    private static final byte[] KEY = "!@#)(*$%123ZXC!@!@#)(NHL".getBytes(StandardCharsets.US_ASCII);

    private static final int[] IP = {
        58,50,42,34,26,18,10,2, 60,52,44,36,28,20,12,4,
        62,54,46,38,30,22,14,6, 64,56,48,40,32,24,16,8,
        57,49,41,33,25,17,9,1, 59,51,43,35,27,19,11,3,
        61,53,45,37,29,21,13,5, 63,55,47,39,31,23,15,7
    };
    private static final int[] FP = {
        40,8,48,16,56,24,64,32, 39,7,47,15,55,23,63,31,
        38,6,46,14,54,22,62,30, 37,5,45,13,53,21,61,29,
        36,4,44,12,52,20,60,28, 35,3,43,11,51,19,59,27,
        34,2,42,10,50,18,58,26, 33,1,41,9,49,17,57,25
    };
    private static final int[] E = {
        32,1,2,3,4,5, 4,5,6,7,8,9, 8,9,10,11,12,13, 12,13,14,15,16,17,
        16,17,18,19,20,21, 20,21,22,23,24,25, 24,25,26,27,28,29, 28,29,30,31,32,1
    };
    private static final int[] P = {
        16,7,20,21,29,12,28,17, 1,15,23,26,5,18,31,10,
        2,8,24,14,32,27,3,9, 19,13,30,6,22,11,4,25
    };
    private static final int[] PC1 = {
        57,49,41,33,25,17,9, 1,58,50,42,34,26,18,
        10,2,59,51,43,35,27, 19,11,3,60,52,44,36,
        63,55,47,39,31,23,15, 7,62,54,46,38,30,22,
        14,6,61,53,45,37,29, 21,13,5,28,20,12,4
    };
    private static final int[] PC2 = {
        14,17,11,24,1,5, 3,28,15,6,21,10, 23,19,12,4,26,8,
        16,7,27,20,13,2, 41,52,31,37,47,55, 30,40,51,45,33,48,
        44,49,39,56,34,53, 46,42,50,36,29,32
    };
    private static final int[] SHIFTS = {1,1,2,2,2,2,2,2,1,2,2,2,2,2,2,1};

    // QQ's cloud QRC cipher has two historical S-box deviations from standard DES.
    private static final int[][] SBOX = {
        {14,4,13,1,2,15,11,8,3,10,6,12,5,9,0,7, 0,15,7,4,14,2,13,1,10,6,12,11,9,5,3,8, 4,1,14,8,13,6,2,11,15,12,9,7,3,10,5,0, 15,12,8,2,4,9,1,7,5,11,3,14,10,0,6,13},
        {15,1,8,14,6,11,3,4,9,7,2,13,12,0,5,10, 3,13,4,7,15,2,8,15,12,0,1,10,6,9,11,5, 0,14,7,11,10,4,13,1,5,8,12,6,9,3,2,15, 13,8,10,1,3,15,4,2,11,6,7,12,0,5,14,9},
        {10,0,9,14,6,3,15,5,1,13,12,7,11,4,2,8, 13,7,0,9,3,4,6,10,2,8,5,14,12,11,15,1, 13,6,4,9,8,15,3,0,11,1,2,12,5,10,14,7, 1,10,13,0,6,9,8,7,4,15,14,3,11,5,2,12},
        {7,13,14,3,0,6,9,10,1,2,8,5,11,12,4,15, 13,8,11,5,6,15,0,3,4,7,2,12,1,10,14,9, 10,6,9,0,12,11,7,13,15,1,3,14,5,2,8,4, 3,15,0,6,10,10,13,8,9,4,5,11,12,7,2,14},
        {2,12,4,1,7,10,11,6,8,5,3,15,13,0,14,9, 14,11,2,12,4,7,13,1,5,0,15,10,3,9,8,6, 4,2,1,11,10,13,7,8,15,9,12,5,6,3,0,14, 11,8,12,7,1,14,2,13,6,15,0,9,10,4,5,3},
        {12,1,10,15,9,2,6,8,0,13,3,4,14,7,5,11, 10,15,4,2,7,12,9,5,6,1,13,14,0,11,3,8, 9,14,15,5,2,8,12,3,7,0,4,10,1,13,11,6, 4,3,2,12,9,5,15,10,11,14,1,7,6,0,8,13},
        {4,11,2,14,15,0,8,13,3,12,9,7,5,10,6,1, 13,0,11,7,4,9,1,10,14,3,5,12,2,15,8,6, 1,4,11,13,12,3,7,14,10,15,6,8,0,5,9,2, 6,11,13,8,1,4,10,7,9,5,0,15,14,2,3,12},
        {13,2,8,4,6,15,11,1,10,9,3,14,5,0,12,7, 1,15,13,8,10,3,7,4,12,5,6,11,0,14,9,2, 7,11,4,1,9,12,14,2,0,6,10,13,15,3,5,8, 2,1,14,7,4,10,8,13,15,12,9,0,3,5,6,11}
    };

    private QqQrcDecoder() {}

    public static String decode(String encryptedHex) throws Exception {
        String hex = encryptedHex == null ? "" : encryptedHex.trim();
        if ((hex.length() & 1) != 0 || !hex.matches("[0-9A-Fa-f]+")) {
            throw new IllegalArgumentException("Invalid QRC hex payload");
        }
        byte[] encrypted = new byte[hex.length() / 2];
        for (int i = 0; i < encrypted.length; i++) {
            encrypted[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        if (encrypted.length == 0 || encrypted.length % 8 != 0) {
            throw new IllegalArgumentException("Invalid QRC block length");
        }

        byte[][][] schedule = new byte[3][16][6];
        keySchedule(KEY, 0, schedule[2], false);
        keySchedule(KEY, 8, schedule[1], true);
        keySchedule(KEY, 16, schedule[0], false);
        byte[] decrypted = new byte[encrypted.length];
        for (int offset = 0; offset < encrypted.length; offset += 8) {
            byte[] block = new byte[8];
            System.arraycopy(encrypted, offset, block, 0, 8);
            byte[] decoded = tripleDesCrypt(block, schedule);
            System.arraycopy(decoded, 0, decrypted, offset, 8);
        }

        try (InflaterInputStream input = new InflaterInputStream(new ByteArrayInputStream(decrypted));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > 2 * 1024 * 1024) throw new IllegalStateException("QRC output too large");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static int bitNum(byte[] data, int offset, int bit, int target) {
        int index = offset + bit / 32 * 4 + 3 - (bit % 32) / 8;
        return (((data[index] & 0xff) >>> (7 - bit % 8)) & 1) << target;
    }

    private static int bitNumIntr(int value, int bit, int target) {
        return ((value >>> (31 - bit)) & 1) << target;
    }

    private static int bitNumIntl(int value, int shift, int target) {
        return ((value << shift) & 0x80000000) >>> target;
    }

    private static int sboxBit(int value) {
        return (value & 0x20) | ((value & 0x1f) >>> 1) | ((value & 1) << 4);
    }

    private static void keySchedule(byte[] key, int offset, byte[][] schedule, boolean encrypt) {
        int[] permC = {56,48,40,32,24,16,8,0,57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35};
        int[] permD = {62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,60,52,44,36,28,20,12,4,27,19,11,3};
        int[] compression = {13,16,10,23,0,4,2,27,14,5,20,9,22,18,11,3,25,7,15,6,26,19,12,1,40,51,30,36,46,54,29,39,50,44,32,47,43,48,38,55,33,52,45,41,49,35,28,31};
        int c = 0;
        int d = 0;
        for (int i = 0, j = 31; i < 28; i++, j--) c |= bitNum(key, offset, permC[i], j);
        for (int i = 0, j = 31; i < 28; i++, j--) d |= bitNum(key, offset, permD[i], j);
        for (int round = 0; round < 16; round++) {
            int shift = SHIFTS[round];
            c = ((c << shift) | (c >>> (28 - shift))) & 0xfffffff0;
            d = ((d << shift) | (d >>> (28 - shift))) & 0xfffffff0;
            int targetRound = encrypt ? round : 15 - round;
            for (int i = 0; i < 6; i++) schedule[targetRound][i] = 0;
            int i = 0;
            for (; i < 24; i++) {
                schedule[targetRound][i / 8] |= (byte) bitNumIntr(c, compression[i], 7 - i % 8);
            }
            for (; i < 48; i++) {
                schedule[targetRound][i / 8] |= (byte) bitNumIntr(d, compression[i] - 27, 7 - i % 8);
            }
        }
    }

    private static void initialPermutation(int[] state, byte[] input) {
        state[0] = bitNum(input,0,57,31)|bitNum(input,0,49,30)|bitNum(input,0,41,29)|bitNum(input,0,33,28)|bitNum(input,0,25,27)|bitNum(input,0,17,26)|bitNum(input,0,9,25)|bitNum(input,0,1,24)|bitNum(input,0,59,23)|bitNum(input,0,51,22)|bitNum(input,0,43,21)|bitNum(input,0,35,20)|bitNum(input,0,27,19)|bitNum(input,0,19,18)|bitNum(input,0,11,17)|bitNum(input,0,3,16)|bitNum(input,0,61,15)|bitNum(input,0,53,14)|bitNum(input,0,45,13)|bitNum(input,0,37,12)|bitNum(input,0,29,11)|bitNum(input,0,21,10)|bitNum(input,0,13,9)|bitNum(input,0,5,8)|bitNum(input,0,63,7)|bitNum(input,0,55,6)|bitNum(input,0,47,5)|bitNum(input,0,39,4)|bitNum(input,0,31,3)|bitNum(input,0,23,2)|bitNum(input,0,15,1)|bitNum(input,0,7,0);
        state[1] = bitNum(input,0,56,31)|bitNum(input,0,48,30)|bitNum(input,0,40,29)|bitNum(input,0,32,28)|bitNum(input,0,24,27)|bitNum(input,0,16,26)|bitNum(input,0,8,25)|bitNum(input,0,0,24)|bitNum(input,0,58,23)|bitNum(input,0,50,22)|bitNum(input,0,42,21)|bitNum(input,0,34,20)|bitNum(input,0,26,19)|bitNum(input,0,18,18)|bitNum(input,0,10,17)|bitNum(input,0,2,16)|bitNum(input,0,60,15)|bitNum(input,0,52,14)|bitNum(input,0,44,13)|bitNum(input,0,36,12)|bitNum(input,0,28,11)|bitNum(input,0,20,10)|bitNum(input,0,12,9)|bitNum(input,0,4,8)|bitNum(input,0,62,7)|bitNum(input,0,54,6)|bitNum(input,0,46,5)|bitNum(input,0,38,4)|bitNum(input,0,30,3)|bitNum(input,0,22,2)|bitNum(input,0,14,1)|bitNum(input,0,6,0);
    }

    private static void inversePermutation(int[] state, byte[] output) {
        output[3]=(byte)(bitNumIntr(state[1],7,7)|bitNumIntr(state[0],7,6)|bitNumIntr(state[1],15,5)|bitNumIntr(state[0],15,4)|bitNumIntr(state[1],23,3)|bitNumIntr(state[0],23,2)|bitNumIntr(state[1],31,1)|bitNumIntr(state[0],31,0));
        output[2]=(byte)(bitNumIntr(state[1],6,7)|bitNumIntr(state[0],6,6)|bitNumIntr(state[1],14,5)|bitNumIntr(state[0],14,4)|bitNumIntr(state[1],22,3)|bitNumIntr(state[0],22,2)|bitNumIntr(state[1],30,1)|bitNumIntr(state[0],30,0));
        output[1]=(byte)(bitNumIntr(state[1],5,7)|bitNumIntr(state[0],5,6)|bitNumIntr(state[1],13,5)|bitNumIntr(state[0],13,4)|bitNumIntr(state[1],21,3)|bitNumIntr(state[0],21,2)|bitNumIntr(state[1],29,1)|bitNumIntr(state[0],29,0));
        output[0]=(byte)(bitNumIntr(state[1],4,7)|bitNumIntr(state[0],4,6)|bitNumIntr(state[1],12,5)|bitNumIntr(state[0],12,4)|bitNumIntr(state[1],20,3)|bitNumIntr(state[0],20,2)|bitNumIntr(state[1],28,1)|bitNumIntr(state[0],28,0));
        output[7]=(byte)(bitNumIntr(state[1],3,7)|bitNumIntr(state[0],3,6)|bitNumIntr(state[1],11,5)|bitNumIntr(state[0],11,4)|bitNumIntr(state[1],19,3)|bitNumIntr(state[0],19,2)|bitNumIntr(state[1],27,1)|bitNumIntr(state[0],27,0));
        output[6]=(byte)(bitNumIntr(state[1],2,7)|bitNumIntr(state[0],2,6)|bitNumIntr(state[1],10,5)|bitNumIntr(state[0],10,4)|bitNumIntr(state[1],18,3)|bitNumIntr(state[0],18,2)|bitNumIntr(state[1],26,1)|bitNumIntr(state[0],26,0));
        output[5]=(byte)(bitNumIntr(state[1],1,7)|bitNumIntr(state[0],1,6)|bitNumIntr(state[1],9,5)|bitNumIntr(state[0],9,4)|bitNumIntr(state[1],17,3)|bitNumIntr(state[0],17,2)|bitNumIntr(state[1],25,1)|bitNumIntr(state[0],25,0));
        output[4]=(byte)(bitNumIntr(state[1],0,7)|bitNumIntr(state[0],0,6)|bitNumIntr(state[1],8,5)|bitNumIntr(state[0],8,4)|bitNumIntr(state[1],16,3)|bitNumIntr(state[0],16,2)|bitNumIntr(state[1],24,1)|bitNumIntr(state[0],24,0));
    }

    private static int feistelLegacy(int state, byte[] key) {
        int t1=bitNumIntl(state,31,0)|((state&0xf0000000)>>>1)|bitNumIntl(state,4,5)|bitNumIntl(state,3,6)|((state&0x0f000000)>>>3)|bitNumIntl(state,8,11)|bitNumIntl(state,7,12)|((state&0x00f00000)>>>5)|bitNumIntl(state,12,17)|bitNumIntl(state,11,18)|((state&0x000f0000)>>>7)|bitNumIntl(state,16,23);
        int t2=bitNumIntl(state,15,0)|((state&0x0000f000)<<15)|bitNumIntl(state,20,5)|bitNumIntl(state,19,6)|((state&0x00000f00)<<13)|bitNumIntl(state,24,11)|bitNumIntl(state,23,12)|((state&0x000000f0)<<11)|bitNumIntl(state,28,17)|bitNumIntl(state,27,18)|((state&0x0000000f)<<9)|bitNumIntl(state,0,23);
        int[] l={(t1>>>24)&255,(t1>>>16)&255,(t1>>>8)&255,(t2>>>24)&255,(t2>>>16)&255,(t2>>>8)&255};
        for(int i=0;i<6;i++)l[i]^=key[i]&255;
        int result=(SBOX[0][sboxBit(l[0]>>>2)]<<28)|(SBOX[1][sboxBit(((l[0]&3)<<4)|(l[1]>>>4))]<<24)|(SBOX[2][sboxBit(((l[1]&15)<<2)|(l[2]>>>6))]<<20)|(SBOX[3][sboxBit(l[2]&63)]<<16)|(SBOX[4][sboxBit(l[3]>>>2)]<<12)|(SBOX[5][sboxBit(((l[3]&3)<<4)|(l[4]>>>4))]<<8)|(SBOX[6][sboxBit(((l[4]&15)<<2)|(l[5]>>>6))]<<4)|SBOX[7][sboxBit(l[5]&63)];
        return bitNumIntl(result,15,0)|bitNumIntl(result,6,1)|bitNumIntl(result,19,2)|bitNumIntl(result,20,3)|bitNumIntl(result,28,4)|bitNumIntl(result,11,5)|bitNumIntl(result,27,6)|bitNumIntl(result,16,7)|bitNumIntl(result,0,8)|bitNumIntl(result,14,9)|bitNumIntl(result,22,10)|bitNumIntl(result,25,11)|bitNumIntl(result,4,12)|bitNumIntl(result,17,13)|bitNumIntl(result,30,14)|bitNumIntl(result,9,15)|bitNumIntl(result,1,16)|bitNumIntl(result,7,17)|bitNumIntl(result,23,18)|bitNumIntl(result,13,19)|bitNumIntl(result,31,20)|bitNumIntl(result,26,21)|bitNumIntl(result,2,22)|bitNumIntl(result,8,23)|bitNumIntl(result,18,24)|bitNumIntl(result,12,25)|bitNumIntl(result,29,26)|bitNumIntl(result,5,27)|bitNumIntl(result,21,28)|bitNumIntl(result,10,29)|bitNumIntl(result,3,30)|bitNumIntl(result,24,31);
    }

    private static byte[] desCrypt(byte[] input, byte[][] key) {
        int[] state=new int[2];
        initialPermutation(state,input);
        for(int round=0;round<15;round++){
            int temp=state[1];
            state[1]=feistelLegacy(state[1],key[round])^state[0];
            state[0]=temp;
        }
        state[0]=feistelLegacy(state[1],key[15])^state[0];
        byte[] output=new byte[8];
        inversePermutation(state,output);
        return output;
    }

    private static byte[] tripleDesCrypt(byte[] input, byte[][][] schedule) {
        return desCrypt(desCrypt(desCrypt(input,schedule[0]),schedule[1]),schedule[2]);
    }
}
