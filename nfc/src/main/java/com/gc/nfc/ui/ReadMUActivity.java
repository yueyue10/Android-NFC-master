package com.gc.nfc.ui;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import android.content.Intent;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareUltralight;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import com.gc.nfc.R;
import com.gc.nfc.Util;
import com.gc.nfc.base.BaseNfcActivity;

/**
 * Created by gc on 2016/12/8.
 */
public class ReadMUActivity extends BaseNfcActivity {
    TextView content_view;
    protected final static byte TRANS_CSU = 6; // 如果等于0x06或者0x09，表示刷卡；否则是充值
    protected final static byte TRANS_CSU_CPX = 9; // 如果等于0x06或者0x09，表示刷卡；否则是充值

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_read_mu);
        content_view = (TextView) findViewById(R.id.content_view);
    }

    @Override
    public void onNewIntent(Intent intent) {
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        parseIntent(intent);
        String[] techList = tag.getTechList();
        boolean haveMifareUltralight = false;
        for (String tech : techList) {
            if (tech.indexOf("MifareUltralight") >= 0) {
                haveMifareUltralight = true;
                break;
            }
        }
        if (!haveMifareUltralight) {
            Toast.makeText(this, "不支持MifareUltralight数据格式", Toast.LENGTH_SHORT).show();
            return;
        }
        String data = readTag(tag);
        if (data != null)
            Toast.makeText(this, data, Toast.LENGTH_SHORT).show();
    }

    private void parseIntent(Intent intent) {
        String nfcAction = intent.getAction(); // 解析该Intent的Action
        if (!NfcAdapter.ACTION_TECH_DISCOVERED.equals(nfcAction)) {
            Log.d("h_bl", "ACTION_TECH_DISCOVERED");
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG); // 获取Tag标签，既可以处理相关信息
            for (String tech : tag.getTechList()) {
                Log.d("h_bl", "tech=" + tech);
            }
            IsoDep isoDep = IsoDep.get(tag);
            String str = "";
            try {
                isoDep.connect(); // 连接
                if (isoDep.isConnected()) {
                    Log.d("h_bl", "isoDep.isConnected"); // 判断是否连接上
                    // 1.select PSF (1PAY.SYS.DDF01)
                    // 选择支付系统文件，它的名字是1PAY.SYS.DDF01。
                    byte[] DFN_PSE = {(byte) '1', (byte) 'P', (byte) 'A', (byte) 'Y', (byte) '.', (byte) 'S', (byte) 'Y', (byte) 'S', (byte) '.', (byte) 'D', (byte) 'D', (byte) 'F', (byte) '0', (byte) '1',};
                    isoDep.transceive(getSelectCommand(DFN_PSE));
                    // 2.选择公交卡应用的名称
                    byte[] DFN_SRV = {(byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x86, (byte) 0x98, (byte) 0x07, (byte) 0x01,};
                    isoDep.transceive(getSelectCommand(DFN_SRV));
                    // 3.读取余额
                    byte[] ReadMoney = {(byte) 0x80, // CLA Class
                            (byte) 0x5C, // INS Instruction
                            (byte) 0x00, // P1 Parameter 1
                            (byte) 0x02, // P2 Parameter 2
                            (byte) 0x04, // Le
                    };
                    byte[] Money = isoDep.transceive(ReadMoney);
                    if (Money != null && Money.length > 4) {
                        int cash = byteToInt(Money, 4);
                        float ba = cash / 100.0f;
                        content_view.setText("余额:" + ba);
                    }
                    // 4.读取所有交易记录
                    byte[] ReadRecord = {(byte) 0x00, // CLA Class
                            (byte) 0xB2, // INS Instruction
                            (byte) 0x01, // P1 Parameter 1
                            (byte) 0xC5, // P2 Parameter 2
                            (byte) 0x00, // Le
                    };
                    byte[] Records = isoDep.transceive(ReadRecord);
                    // 处理Record
                    Log.d("h_bl", "总消费记录" + Records);
                    ArrayList<byte[]> ret = parseRecords(Records);
                    List<String> retList = parseRecordsToStrings(ret);
                    content_view.append("\n" + "消费记录如下：");
                    for (String string : retList) {
                        Log.d("h_bl", "消费记录" + string);
                        content_view.append("\n" + string);
                    }

                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (isoDep != null) {
                    try {
                        isoDep.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private byte[] getSelectCommand(byte[] aid) {
        final ByteBuffer cmd_pse = ByteBuffer.allocate(aid.length + 6);
        cmd_pse.put((byte) 0x00) // CLA Class
                .put((byte) 0xA4) // INS Instruction
                .put((byte) 0x04) // P1 Parameter 1
                .put((byte) 0x00) // P2 Parameter 2
                .put((byte) aid.length) // Lc
                .put(aid).put((byte) 0x00); // Le
        return cmd_pse.array();
    }

    // byteArray转化为int
    private int byteToInt(byte[] b, int n) {
        int ret = 0;
        for (int i = 0; i < n; i++) {
            ret = ret << 8;
            ret |= b[i] & 0x00FF;
        }
        if (ret > 100000 || ret < -100000)
            ret -= 0x80000000;
        return ret;
    }

    /**
     * 整条Records解析成ArrayList<byte[]>
     *
     * @param Records
     * @return
     */
    private ArrayList<byte[]> parseRecords(byte[] Records) {
        int max = Records.length / 23;
        Log.d("h_bl", "消费记录有" + max + "条");
        ArrayList<byte[]> ret = new ArrayList<byte[]>();
        for (int i = 0; i < max; i++) {
            byte[] aRecord = new byte[23];
            for (int j = 23 * i, k = 0; j < 23 * (i + 1); j++, k++) {
                aRecord[k] = Records[j];
            }
            ret.add(aRecord);
        }
        for (byte[] bs : ret) {
            Log.d("h_bl", "消费记录有byte[]" + bs); // 有数据。解析正确。
        }
        return ret;
    }

    /**
     * ArrayList<byte[]>记录分析List<String> 一条记录是23个字节byte[] data，对其解码如下
     * data[0]-data[1]:index data[2]-data[4]:over,金额溢出?？？ data[5]-data[8]:交易金额
     * ？？代码应该是（5，4） data[9]:如果等于0x06或者0x09，表示刷卡；否则是充值
     * data[10]-data[15]:刷卡机或充值机编号
     * data[16]-data[22]:日期String.format("%02X%02X.%02X.%02X %02X:%02X:%02X"
     * ,data[16], data[17], data[18], data[19], data[20], data[21], data[22]);
     */
    private List<String> parseRecordsToStrings(ArrayList<byte[]>... Records) {
        List<String> recordsList = new ArrayList<String>();
        for (ArrayList<byte[]> record : Records) {
            if (record == null)
                continue;
            for (byte[] v : record) {
                StringBuilder r = new StringBuilder();
                int cash = Util.toInt(v, 5, 4);
                char t = (v[9] == TRANS_CSU || v[9] == TRANS_CSU_CPX) ? '-' : '+';
                r.append(String.format("%02X%02X.%02X.%02X %02X:%02X ", v[16], v[17], v[18], v[19], v[20], v[21], v[22]));
                r.append("   " + t).append(Util.toAmountString(cash / 100.0f));
                String aLog = r.toString();
                recordsList.add(aLog);
            }
        }
        return recordsList;
    }
    public String readTag(Tag tag) {
        MifareUltralight ultralight = MifareUltralight.get(tag);
        try {
            ultralight.connect();
            byte[] data = ultralight.readPages(4);
            return new String(data, Charset.forName("GB2312"));
        } catch (Exception e) {
        } finally {
            try {
                ultralight.close();
            } catch (Exception e) {
            }
        }
        return null;
    }
}
