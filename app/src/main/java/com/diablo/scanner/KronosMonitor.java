package com.diablo.scanner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Independent multi-timeframe market monitor. It uses real BigQuote OHLC candles,
 * not screenshot pixels or fabricated candles. Its technical setup is a rules-based
 * monitor, not a claim that the upstream Kronos neural model has run.
 */
public final class KronosMonitor {
    private KronosMonitor() {}

    public static final class Snapshot {
        public final String symbol, higherTf, middleTf, entryTf;
        public final String higherTrend, middleTrend, entryTrend, status, details;
        public final double entry, stopLoss, takeProfit, riskReward;
        public final boolean validSetup;
        public final long updatedAt;

        Snapshot(String symbol, String higherTf, String middleTf, String entryTf,
                 String higherTrend, String middleTrend, String entryTrend,
                 String status, String details, double entry, double stopLoss,
                 double takeProfit, double riskReward, boolean validSetup, long updatedAt) {
            this.symbol=symbol; this.higherTf=higherTf; this.middleTf=middleTf; this.entryTf=entryTf;
            this.higherTrend=higherTrend; this.middleTrend=middleTrend; this.entryTrend=entryTrend;
            this.status=status; this.details=details; this.entry=entry; this.stopLoss=stopLoss;
            this.takeProfit=takeProfit; this.riskReward=riskReward; this.validSetup=validSetup;
            this.updatedAt=updatedAt;
        }
    }

    private static final class Bar {
        long time; double open, high, low, close;
        Bar(JSONObject o) throws Exception {
            Object raw=o.opt("timestamp");
            if (raw instanceof Number) { time=((Number)raw).longValue(); if (time < 100000000000L) time *= 1000L; }
            else {
                String value=String.valueOf(raw).trim();
                if (value.matches("\\d{10,13}")) { time=Long.parseLong(value); if (time < 100000000000L) time *= 1000L; }
                else time=java.time.Instant.parse(value).toEpochMilli();
            }
            open=o.getDouble("open"); high=o.getDouble("high"); low=o.getDouble("low"); close=o.getDouble("close");
            if (!(open>0 && high>0 && low>0 && close>0) ||
                high < Math.max(open, Math.max(close, low)) || low > Math.min(open, Math.min(close, high)))
                throw new IllegalArgumentException("Invalid OHLC candle.");
        }
    }

    public static Snapshot evaluate(String symbol, String higherTf, String middleTf, String entryTf) throws Exception {
        List<Bar> higher=fetch(symbol, higherTf), middle=fetch(symbol, middleTf), entryBars=fetch(symbol, entryTf);
        String ht=trend(higher), mt=trend(middle), et=trend(entryBars);
        Bar last=entryBars.get(entryBars.size()-1);
        double atr=atr(entryBars);
        boolean aligned=(ht.equals("BULLISH") && mt.equals("BULLISH")) ||
                        (ht.equals("BEARISH") && mt.equals("BEARISH"));
        Bar previous=entryBars.get(entryBars.size()-2);
        boolean entryAligned=(ht.equals("BULLISH") && mt.equals("BULLISH") && et.equals("BULLISH")) ||
                              (ht.equals("BEARISH") && mt.equals("BEARISH") && et.equals("BEARISH"));
        boolean entryTrigger=(ht.equals("BULLISH") && last.close>previous.high) ||
                             (ht.equals("BEARISH") && last.close<previous.low);
        String direction=ht.equals("BULLISH") ? "UP" : ht.equals("BEARISH") ? "DOWN" : "WAIT";
        double entry=0, sl=0, tp=0, rr=0;
        boolean valid=false;
        String status="WAIT — MONITORING FOR ENTRY";
        String reason="Trend alignment or entry conditions are not yet confirmed. Monitoring remains active.";
        if (entryAligned && entryTrigger && atr>0 && last.close>0) {
            entry=last.close;
            double risk=Math.max(atr*1.5, entry*0.0002);
            double reward=risk*2.0;
            if ("UP".equals(direction)) { sl=entry-risk; tp=entry+reward; }
            else { sl=entry+risk; tp=entry-reward; }
            valid=sl>0 && tp>0 && ("UP".equals(direction) ? sl<entry && tp>entry : sl>entry && tp<entry);
            if (valid) { rr=reward/risk; status="ENTRY SETUP DETECTED"; reason="All three timeframe trends align. Entry/SL/TP calculated from the latest completed entry-timeframe close and ATR-based risk rules; not a guarantee of outcome."; }
        } else if (!aligned) {
            reason="Higher and middle timeframe trends disagree. Monitoring continues; no entry levels are issued.";
        } else if (!entryAligned) {
            reason="Higher and middle timeframes agree, but the entry timeframe has not confirmed. Monitoring continues.";
        } else if (!entryTrigger) {
            reason="All three trends align, but the entry-timeframe breakout trigger has not occurred. Monitoring continues; no premature entry is issued.";
        }
        String details="KRONOS MULTI-TIMEFRAME MONITOR (rules-based)\n"
                +"Market: "+symbol+"\n"
                +higherTf+" trend: "+ht+"\n"+middleTf+" trend: "+mt+"\n"+entryTf+" entry trend: "+et+"\n"
                +"Monitor: ACTIVE — "+status+"\n"+reason+"\n"
                +(valid ? "Direction: "+direction+"\nEntry: "+fmt(entry)+"\nStop-loss: "+fmt(sl)+"\nTake-profit: "+fmt(tp)+"\nRisk/reward: 1:"+String.format(Locale.US,"%.2f",rr)+"\n"
                         : "Entry / SL / TP: WAITING FOR A VALID SETUP\n")
                +"Data source: BigQuote completed candles. Updated: "+java.time.Instant.now()+"\n"
                +"Note: this is a transparent technical rules layer, not verified Kronos neural-model inference.";
        return new Snapshot(symbol,higherTf,middleTf,entryTf,ht,mt,et,status,details,entry,sl,tp,rr,valid,System.currentTimeMillis());
    }

    private static List<Bar> fetch(String symbol, String interval) throws Exception {
        int seconds=intervalSeconds(interval);
        String safe=symbol.trim().toUpperCase(Locale.ROOT);
        if (!safe.matches("[A-Z0-9._-]{1,40}")) throw new IllegalArgumentException("Invalid BigQuote symbol.");
        URL url=new URL("https://biquote.io/api/"+java.net.URLEncoder.encode(safe,"UTF-8")
                +"/ohlc?interval="+java.net.URLEncoder.encode(interval,"UTF-8")+"&limit=200");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        try {
            c.setRequestMethod("GET"); c.setConnectTimeout(12000); c.setReadTimeout(20000);
            c.setRequestProperty("Accept","application/json"); c.setRequestProperty("User-Agent","DiabloScanner/1.0");
            int code=c.getResponseCode();
            InputStream in=code>=200 && code<300 ? c.getInputStream() : c.getErrorStream();
            String body;
            try(InputStream input=in; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] b=new byte[4096]; int n; while(input!=null && (n=input.read(b))!=-1) out.write(b,0,n);
                body=out.toString("UTF-8");
            }
            if(code<200 || code>=300) throw new IllegalStateException("BigQuote HTTP "+code+" for "+interval);
            JSONArray bars=new JSONObject(body).optJSONArray("bars");
            if(bars==null) throw new IllegalStateException("BigQuote returned no bars for "+interval);
            List<Bar> result=new ArrayList<>();
            for(int i=0;i<bars.length();i++) {
                JSONObject o=bars.optJSONObject(i); if(o==null || o.optBoolean("isOpen",false)) continue;
                try { result.add(new Bar(new JSONObject().put("timestamp",o.opt("openTime"))
                    .put("open",o.getDouble("open")).put("high",o.getDouble("high"))
                    .put("low",o.getDouble("low")).put("close",o.getDouble("close")))); }
                catch(Exception ignored) {}
            }
            Collections.sort(result, Comparator.comparingLong(x->x.time));
            if(result.size()<32) throw new IllegalStateException("Only "+result.size()+" completed candles for "+interval+"; at least 32 are required.");
            long step=seconds*1000L;
            for(int i=1;i<result.size();i++) {
                long d=result.get(i).time-result.get(i-1).time;
                if(d<step || d%step!=0) throw new IllegalStateException("BigQuote candle timestamps do not align to "+interval+".");
            }
            return result.subList(Math.max(0,result.size()-100),result.size());
        } finally { c.disconnect(); }
    }

    private static String trend(List<Bar> b) {
        int n=b.size(); double recent=ema(b, Math.min(9,n)), slow=ema(b, Math.min(21,n));
        Bar last=b.get(n-1), prev=b.get(n-2);
        if(recent>slow && last.close>=prev.close) return "BULLISH";
        if(recent<slow && last.close<=prev.close) return "BEARISH";
        return "NEUTRAL";
    }
    private static double ema(List<Bar> b,int period) {
        double k=2.0/(period+1.0), value=b.get(0).close;
        for(int i=1;i<b.size();i++) value=b.get(i).close*k+value*(1-k);
        return value;
    }
    private static double atr(List<Bar> b) {
        int start=Math.max(1,b.size()-14); double sum=0; int count=0;
        for(int i=start;i<b.size();i++) {
            Bar x=b.get(i), p=b.get(i-1);
            sum+=Math.max(x.high-x.low,Math.max(Math.abs(x.high-p.close),Math.abs(x.low-p.close))); count++;
        }
        return count==0?0:sum/count;
    }
    private static int intervalSeconds(String tf) {
        if("1m".equals(tf))return 60;if("2m".equals(tf))return 120;if("3m".equals(tf))return 180;
        if("5m".equals(tf))return 300;if("15m".equals(tf))return 900;if("30m".equals(tf))return 1800;
        if("1h".equals(tf))return 3600;if("2h".equals(tf))return 7200;if("4h".equals(tf))return 14400;
        if("1d".equals(tf))return 86400;throw new IllegalArgumentException("Unsupported timeframe: "+tf);
    }
    private static String fmt(double x) { return String.format(Locale.US,"%.6f",x); }
}
