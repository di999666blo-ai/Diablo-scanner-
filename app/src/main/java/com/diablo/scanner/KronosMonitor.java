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
        // Top-down workflow: 4H bias + levels -> 15M setup around those levels -> 1M trigger.
        List<Bar> higher=fetch(symbol, higherTf), middle=fetch(symbol, middleTf), entryBars=fetch(symbol, entryTf);
        String ht=trend(higher), mt=trend(middle), et=trend(entryBars);
        Bar hLast=higher.get(higher.size()-1), mLast=middle.get(middle.size()-1), mPrev=middle.get(middle.size()-2);
        Bar last=entryBars.get(entryBars.size()-1), prev=entryBars.get(entryBars.size()-2), prev2=entryBars.get(entryBars.size()-3);
        double hAtr=atr(higher), mAtr=atr(middle), eAtr=atr(entryBars);

        // Recent closed 4H structure plus the latest closed 4H candle's extremes.
        int hFrom=Math.max(0,higher.size()-21);
        double structureHigh=Double.NEGATIVE_INFINITY, structureLow=Double.POSITIVE_INFINITY;
        for(int i=hFrom;i<higher.size();i++) {
            structureHigh=Math.max(structureHigh,higher.get(i).high);
            structureLow=Math.min(structureLow,higher.get(i).low);
        }
        double resistance=Math.max(structureHigh,hLast.high), support=Math.min(structureLow,hLast.low);
        double current=mLast.close;
        double zoneTol=Math.max(Math.max(mAtr*0.65, hAtr*0.12), current*0.00015);
        boolean bullish="BULLISH".equals(ht), bearish="BEARISH".equals(ht), trendKnown=bullish||bearish;

        // 15M setup: a pullback/rejection at a 4H zone OR a breakout/retest.
        boolean nearSupport=Math.abs(current-support)<=zoneTol || (mLast.low<=support+zoneTol && current>=support-zoneTol);
        boolean nearResistance=Math.abs(current-resistance)<=zoneTol || (mLast.high>=resistance-zoneTol && current<=resistance+zoneTol);
        boolean bullish15Reject=nearSupport && mLast.close>=mLast.open && mLast.close>=mPrev.close;
        boolean bearish15Reject=nearResistance && mLast.close<=mLast.open && mLast.close<=mPrev.close;
        boolean bullish15Break=mLast.close>resistance && (mPrev.close>resistance || mLast.low<=resistance+zoneTol);
        boolean bearish15Break=mLast.close<support && (mPrev.close<support || mLast.high>=support-zoneTol);
        boolean setup15=bullish ? (bullish15Reject||bullish15Break) : bearish ? (bearish15Reject||bearish15Break) : false;

        // Flexible 1M trigger: rejection, engulfing, or micro-structure break; no strict EMA alignment gate.
        double microTol=Math.max(eAtr*0.15,current*0.00003);
        boolean bullReject=last.low<=Math.min(prev.low,prev2.low)+microTol && last.close>last.open && last.close>prev.close;
        boolean bearReject=last.high>=Math.max(prev.high,prev2.high)-microTol && last.close<last.open && last.close<prev.close;
        boolean bullEngulf=last.close>last.open && prev.close<prev.open && last.close>=prev.open && last.open<=prev.close;
        boolean bearEngulf=last.close<last.open && prev.close>prev.open && last.close<=prev.open && last.open>=prev.close;
        boolean bullBreak=last.close>Math.max(prev.high,prev2.high), bearBreak=last.close<Math.min(prev.low,prev2.low);
        boolean entryTrigger=bullish ? (bullReject||bullEngulf||bullBreak) : bearish ? (bearReject||bearEngulf||bearBreak) : false;

        String direction=bullish ? "UP" : bearish ? "DOWN" : "WAIT";
        double entry=0, sl=0, tp=0, rr=0;
        boolean valid=false;
        String status="WAIT — MONITORING FOR ENTRY", reason;
        if(!trendKnown) {
            reason="4H trend is neutral/ranging. No direction is forced; monitoring continues.";
        } else if(!setup15) {
            reason="4H bias established. Waiting for 15M price action to reach/reject a 4H structure zone or confirm a breakout/retest.";
        } else if(!entryTrigger) {
            reason="15M setup detected around 4H structure. Waiting for a 1M rejection, engulfing candle, or micro-structure break.";
        } else if(eAtr<=0 || last.close<=0) {
            reason="Entry trigger seen but valid volatility/risk data is unavailable; no levels issued.";
        } else {
            entry=last.close;
            double riskFloor=Math.max(eAtr*1.35,entry*0.00015);
            double structuralStop=bullish ? Math.min(last.low,Math.min(prev.low,prev2.low)) : Math.max(last.high,Math.max(prev.high,prev2.high));
            double stopPad=Math.max(eAtr*0.10,entry*0.00003);
            sl=bullish ? Math.min(entry-riskFloor,structuralStop-stopPad) : Math.max(entry+riskFloor,structuralStop+stopPad);
            double actualRisk=Math.abs(entry-sl), reward=actualRisk*2.0;
            tp=bullish ? entry+reward : entry-reward;
            valid=actualRisk>0 && sl>0 && tp>0 && (bullish ? sl<entry && tp>entry : sl>entry && tp<entry);
            if(valid) {
                rr=Math.abs(tp-entry)/actualRisk;
                status="ENTRY SETUP DETECTED";
                reason="4H directional bias + 15M structure setup + 1M trigger confirmed. Levels are rule-based estimates; verify spread and live price.";
            } else reason="Entry pattern detected, but calculated risk levels failed validation. Monitoring continues.";
        }
        String details="KRONOS TOP-DOWN MARKET MONITOR (rules-based)\n"
                +"Market: "+symbol+"\n"+higherTf+" trend: "+ht+"\n"
                +"Last closed "+higherTf+" candle: O "+fmt(hLast.open)+" / H "+fmt(hLast.high)+" / L "+fmt(hLast.low)+" / C "+fmt(hLast.close)+"\n"
                +"4H structure support: "+fmt(support)+"\n4H structure resistance: "+fmt(resistance)+"\n"
                +middleTf+" context: "+mt+" — "+(setup15?"SETUP DEVELOPING/CONFIRMED":"WATCHING 4H ZONES")+"\n"
                +entryTf+" entry trigger: "+(entryTrigger?"CONFIRMED":"WAITING")+" ("+(bullReject||bullEngulf||bullBreak?"bullish pattern":bearReject||bearEngulf||bearBreak?"bearish pattern":"no trigger yet")+")\n"
                +"Monitor: ACTIVE — "+status+"\n"+reason+"\n"
                +(valid ? "Direction: "+direction+"\nEntry: "+fmt(entry)+"\nStop-loss: "+fmt(sl)+"\nTake-profit: "+fmt(tp)+"\nRisk/reward: 1:"+String.format(Locale.US,"%.2f",rr)+"\n"
                         : "Entry / SL / TP: WAITING FOR A VALID SETUP\n")
                +"Data source: BigQuote completed candles. Updated: "+java.time.Instant.now()+"\n"
                +"Note: transparent technical rules layer; this output alone does not prove neural Kronos inference.";
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
