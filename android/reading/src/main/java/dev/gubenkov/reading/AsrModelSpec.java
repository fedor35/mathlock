package dev.gubenkov.reading;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Манифест акустической CTC-модели (model.json рядом с .onnx). Модель меняется без правки кода:
 * GigaAM-v3 (ru), дообученная на KSC2 (kk), фонемная wav2vec2/MMS (en, по фонемам) и т.д.
 * {
 *  "id":"gigaam3-ctc-ru", "langs":["ru"], "units":"letters",
 *  "frontend":{"type":"logmel","n_mels":64,"n_fft":320,"hop":160,"mel":"htk"},   // или {"type":"raw"} для wav2vec2
 *  "vocab":[" ","а",…], "blank":33, "subsampling":4,
 *  "files":{"cpu":"gigaam_ctc_int8.onnx","htp_fp16":"gigaam_ctc_T1600_fp32.onnx","htp_int":"gigaam_ctc_T1600_qdq.onnx"},
 *  "static_frames":1600
 * }
 */
public final class AsrModelSpec {
    public final String id, units, frontend;
    public final String[] langs, vocab;
    public final int blank, subsampling, staticFrames, nMels;
    public final String fileCpu, fileHtpFp16, fileHtpInt;

    public AsrModelSpec(JSONObject j) throws org.json.JSONException {
        id = j.getString("id");
        units = j.optString("units", "letters");
        JSONArray l = j.getJSONArray("langs"); langs = new String[l.length()];
        for (int i = 0; i < langs.length; i++) langs[i] = l.getString(i);
        JSONObject fe = j.getJSONObject("frontend");
        frontend = fe.getString("type");
        nMels = fe.optInt("n_mels", 64);
        JSONArray v = j.getJSONArray("vocab"); vocab = new String[v.length()];
        for (int i = 0; i < vocab.length; i++) vocab[i] = v.getString(i);
        blank = j.optInt("blank", vocab.length);
        subsampling = j.optInt("subsampling", 4);
        staticFrames = j.optInt("static_frames", 0);
        JSONObject f = j.getJSONObject("files");
        fileCpu = f.optString("cpu", null); fileHtpFp16 = f.optString("htp_fp16", null); fileHtpInt = f.optString("htp_int", null);
    }

    public boolean supports(String lang) { for (String s : langs) if (s.equals(lang)) return true; return false; }
}
