package com.example.demo.service.Mongo;

import com.example.demo.entity.Field;
import com.example.demo.service.Mongo.SensorValueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.Field;
import com.example.demo.entity.MongoEntity.SimulationResult;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import static java.lang.Math.*;

@Service
public class FieldSimulator {
    public static final double _APPi = 1.00 * 1.00; // Area per plant (row x interRow spacing) (m2)
    public static final int _nsl = 5; // number of soil layer
    public static final double _lw = 0.9 / _nsl; // depth/_nsl // thickness of a layer (m) _depth = 0.9
    public static final double _lvol = _lw * _APPi; // depth*_APPI/_nsl // volume of one soil layer
    public static final double _BD = 1360; // soild bulk density in (kg/m3) # Burrium 1.36, Ratchaburi 1.07 g.cm3
    public static double _cuttingDryMass = 75.4; // g
    public static double _leafAge = 75;
    public static double _SRL = 39.0; // m/g
    public static boolean _zerodrain = true;
    // todo needs to be based on planting date provided by user then weather should start at right point
    public static double _iTheta = 0.2;
    public static double _thm = 0.18; //drier todo make
    public static double _ths = 0.43;//0.27; //0.43field capacity, not saturation todo rename
    public static double _thr = 0.065;//0.015; //0.065 residual water content
    public static double _thg = 0.02;
    public static double _rateFlow = 1.3;
    // order of value weather in list weatherData
    final int _iTime = 0;
    final int _iDOY = 1;
    final int _iRadiation = 2;
    final int _iRain = 4;
    final int _iRH = 5;
    final int _iTemp = 3;
    final int _iWind = 6;
    final int _iIrrigation = 8;
    public double _fcthresold;
    public double _IrrigationRate;
    private double _autoIrrigateTime = -1;
    @Autowired
    private WeatherProvider weatherProvider;

    // =========================
    // ENTRY POINT
    // =========================

    public static double relTheta(double th) {
        return lim((th - _thr) / (_ths - _thr), 0, 1);
    }

    public static double lim(double x, double xl, double xu) {
        if (x > xu) {
            return xu;
        } else if (x < xl) {
            return xl;
        } else {
            return x;
        }
    }

    // convert Date to doy
    // Static biến dùng để nhớ offset và lần gọi trước
    private static double previousDoy = -1;
    private static double doyOffset = 0;
    public static double getDoy(Date sd) {
        Calendar rsd = Calendar.getInstance();
        rsd.setTime(sd);
        rsd.set(Calendar.MONTH, Calendar.JANUARY);
        rsd.set(Calendar.DAY_OF_MONTH, 1);
        rsd.set(Calendar.HOUR_OF_DAY, 0);
        rsd.set(Calendar.MINUTE, 0);
        rsd.set(Calendar.SECOND, 0);

        double doy = (double) ((sd.getTime() - rsd.getTime().getTime()) / (1000 * 60 * 60 * 24));
        doy += sd.getHours() / 24.0 +
                sd.getMinutes() / (24.0 * 60.0) +
                sd.getSeconds() / (24.0 * 60.0 * 60.0);

        // Nếu ngày bị "reset về đầu năm" (tức giảm so với ngày trước) → tăng offset
        if (previousDoy >= 0 && doy < previousDoy) {
            doyOffset += 365; // hoặc check năm nhuận nếu cần chính xác hơn
        }

        previousDoy = doy;

        return doy + doyOffset;
    }

    public static List<Double> multiplyLists(List<Double> l1, List<Double> l2) {
        int n = min(l1.size(), l2.size());
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            result.add(l1.get(i) * l2.get(i));
        }
        return result;
    }

    public static List<Double> multiplyListsWithConstant(List<Double> l, double c) {
        List<Double> result = new ArrayList<>();
        for (Double number : l) {
            result.add(number * c);
        }
        return result;
    }

    public static double monod(double conc, double Imax, double Km) {
        double pc = Math.max(0.0, conc);
        return pc * Imax / (Km + pc);
    }

    public static double logistic(double x, double x0, double xc, double k, double m) {
        return x0 + (m - x0) / (1 + exp(-k * (x - xc)));
    }

    public static double photoFixMean(double ppfd, double lai,
                                      double kdf, double Pn_max, double phi, double k) {
        double r = 0;
        int n = 30;
        double b = 4 * k * Pn_max;
        for (int i = 0; i < n; ++i) {
            double kf = exp(kdf * lai * (i + 0.5) / n);
            double I = ppfd * kf;
            double x0 = phi * I;
            double x1 = x0 + Pn_max;
            double p = x1 - sqrt(x1 * x1 - b * x0);
            r += p;
        }
        r *= -12e-6 * 60 * 60 * 24 * kdf * _APPi * lai / n / (2 * k);
        return r;
    }

    public static double fSLA(double ct) {
        return logistic(ct, 0.04, 60, 0.1, 0.0264);
    }

    public static double fKroot(double th, double rl) {
        double rth = relTheta(th);
        double kadj = min(1.0, pow(rth / 0.4, 1.5));
        double Ksr = 0.01;
        return Ksr * kadj * rl;
    }

    public static double fWaterStress(double minV, double maxV, double the) {
        double s = 1 / (maxV - minV);
        double i = -1 * minV * s;
        return lim(i + s * relTheta(the), 0, 1);
    }

    public static double getStress(double clab, double dm, double low, double high, boolean swap) {
        if (high < -9999.0) {
            high = low + 0.01;
        }
        double dm1 = Math.max(dm, 0.001);
        double cc = clab / dm1;
        double rr = lim(((cc - low) / (high - low)), 0, 1);

        if (swap) {
            rr = 1.0 - rr;
        }
        return rr;
    }

    public SimulationResult simulate(Field field) {

        // 🔥 preload toàn bộ data 1 lần
        weatherProvider.loadData(
                field.getId(),
                field.getStartTime(),
                new Date()
        );

        _autoIrrigateTime = -1;
        double t = 0;
        double dt = 1.0 / 24;

        Date start = field.getStartTime();
        Date end = new Date();

        long days = (end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24);

        if (days <= 0) {
            days = 1;
        }

        List<Double> y = ode2initValues();
        List<List<Double>> results = new ArrayList<>();

        double totalWater = 0;

        while (t < days) {

            List<Double> wd = weatherProvider.getWeather(t);

            // 🔥 irrigation logic của bạn
            double irrigation = calculateIrrigation(field, y, wd, t);

            wd.set(4, irrigation);

            totalWater += irrigation;

            // 🔥 RK4 dùng ode2 của bạn
            rk4Step(field, t, y, wd, dt);

            // 🔥 lưu kết quả (giữ mapping cũ)
            saveStep(results, y, wd, t);

            t += dt;
        }

        SimulationResult result = new SimulationResult();
        result.setRawResults(results);
        result.setTotalWater(totalWater);
        double expectedYield = results.get(0).get(results.get(0).size() - 1);
        result.setExpectedYield(expectedYield);
        return result;
    }

    private void rk4Step(Field field, double t, List<Double> y, List<Double> wd, double dt) {

        List<Double> k1 = ode2(field, t, y, wd);
        List<Double> y2 = add(y, mul(k1, dt / 2));

        List<Double> k2 = ode2(field, t + dt / 2, y2, wd);
        List<Double> y3 = add(y, mul(k2, dt / 2));

        List<Double> k3 = ode2(field, t + dt / 2, y3, wd);
        List<Double> y4 = add(y, mul(k3, dt));

        List<Double> k4 = ode2(field, t + dt, y4, wd);

        for (int i = 0; i < y.size(); i++) {
            double val = y.get(i) + dt / 6 *
                    (k1.get(i) + 2*k2.get(i) + 2*k3.get(i) + k4.get(i));
            y.set(i, val);
        }
    }

    public void ode2InitModel(Double startTime, Double endTime) {
    }

    public List<Double> ode2initValuesTime0(Field field) {

        List<Double> yi = new ArrayList<>();

        for (int index = 0; index < 9 + _nsl * 5; ++index) {
            yi.add(0.0);
        }

        List<Double> iTheta = new ArrayList<>();
        for (int index = 0; index < _nsl; ++index) {
            iTheta.add(_iTheta + index * _thg);
        }

        List<Double> iNcont = new ArrayList<>();
        iNcont.add(39.830);
        iNcont.add(10.105);
        iNcont.add(16.050);
        iNcont.add(8.0);
        iNcont.add(8.0);

        for (int index = 5; index < 15; ++index) {
            iNcont.add(0.0);
        }

        double iNRT = 6.0;

        yi.set(1, _cuttingDryMass);
        yi.set(6, _cuttingDryMass);

        yi.set(9 + _nsl, iNRT);

        for (int i = 0; i < _nsl; ++i) {

            yi.set(9 + 2 * _nsl + i, iTheta.get(i));

            // 🔥 FIX Ở ĐÂY
            yi.set(
                9 + 3 * _nsl + i,
                iNcont.get(i) * field.getFertilizationLevel() / 100
            );

            yi.set(
                9 + 4 * _nsl + i,
                _cuttingDryMass * 30.0 / _nsl
            );
        }

        yi.add(0.0);
        yi.add(0.0);
        yi.add(0.0);
        yi.add(0.0);
        yi.add(0.0);
        yi.add(0.0);
        yi.add(0.0);

        return yi;
    }
    public List<Double> ode2(Field field, double ct, List<Double> y, List<Double> wd) {
        int cnt = -1;
        double LDM = y.get(++cnt); // Leaf Dry Mass (g)
        double SDM = y.get(++cnt); // Stem Dry Mass (g)
        double RDM = y.get(++cnt); // Root Dry Mass (g)
        double SRDM = y.get(++cnt); // Storage Root Dry Mass (g)
        double LA = y.get(++cnt); // Leaf Area (m2)
        System.out.printf("\n-------------------Input----------------------------\n");
        System.out.printf("LDM: "+ LDM + "\n");
        System.out.printf("SDM: "+ SDM + "\n");
        System.out.printf("RDM: "+ RDM + "\n");
        System.out.printf("SRDM: "+ SRDM + "\n");
        System.out.printf("LA: "+ LA + "\n");

        double mDMl = y.get(++cnt); //intgrl("mDMl", 0, "mGRl");
        //double mDMld = y.get(7);//intgrl("mDMld", 0, "mGRld");
        double mDMs = y.get(++cnt); //intgrl("mDMs", cuttingDryMass, "mGRs");
        //double mDM = y.get(9);//intgrl("mDM", 0, "mGR");
        ++cnt; //double mDMsr = y.get(++cnt); //intgrl("mDMsr", 0, "mGRsr");
        //double TR = intgrl("TR", 0, "RR"); // Total Respiration (g C)
        double Clab = y.get(++cnt); // labile carbon pool
        ++cnt;
        List<Double> rlL = new ArrayList<>(y.subList(cnt, cnt += _nsl)); //Root length per layer (m)
        //double RL = sumList(RL_l); // Root length (m)

        List<Double> nrtL = new ArrayList<>(y.subList(cnt, cnt += _nsl)); //Root tips per layer
        double NRT = 0;
        for (double element : nrtL) {
            NRT += element;
        }
        List<Double> thetaL = new ArrayList<>(y.subList(cnt, cnt += _nsl)); //volumetric soil water content for each layer

        //double Ncont_l = intgrl("Ncont",[4.83+35, 10.105, 16.05]*_lvol*BD,"NcontR");// N-content in a soil layer (mg);
        List<Double> ncontL = new ArrayList<>(y.subList(cnt, cnt += _nsl));
        List<Double> nuptL = new ArrayList<>(y.subList(cnt, cnt += _nsl));
        double Nupt = 0;
        for (double element : nuptL) {
            Nupt += element;
        }


        double TDM = LDM + SDM + RDM + SRDM + Clab;
        double cDm = 0.43;
        double leafTemp = wd.get(_iTemp);
        double TSphot = lim((-0.832097717 + 0.124485738 * leafTemp - 0.002114081 * Math.pow(leafTemp, 2)), 0, 1);
        double TSshoot = lim((-1.5 + 0.125 * leafTemp), 0, 1) * lim((7.4 - 0.2 * leafTemp), 0, 1);
        double TSroot = 1.0;
        System.out.printf("\n-------------------Process---------------------------- \n");
        System.out.printf("TDM: "+ TDM + "\n");
        System.out.printf("TSphot: "+ TSphot + "\n");
        System.out.printf("TSshoot : "+ TSshoot + "\n");
        System.out.printf("RDM: "+ RDM + "\n");

        List<Double> krootL = new ArrayList<>();
        for (int i = 0; i < _nsl; ++i) {
            krootL.add(fKroot(thetaL.get(i), rlL.get(i)));
        }
        //sums up all elements.
        double Kroot = krootL.stream().mapToDouble(Double::doubleValue).sum();
        Kroot = Math.max(1e-8, Kroot);

        double thEquiv;
        if (Kroot > 1e-8) {
            double sumThetaKroot = 0.0;
            for (int i = 0; i < _nsl; ++i) {
                sumThetaKroot += thetaL.get(i) * krootL.get(i);
            }
            thEquiv = sumThetaKroot / Kroot;
        } else {
            thEquiv = thetaL.get(0);
        }

        double WStrans = fWstress(0.05, 0.5, thEquiv);
        double WSphot = fWstress(0.05, 0.3, thEquiv);
        System.out.printf("WStrans: "+ WStrans + "\n");
        System.out.printf("WSphot: "+ WSphot + "\n");

        double WSshoot = fWstress(0.2, 0.55, thEquiv);
        double WSroot = 1;
        double WSleafSenescence = 1.0 - fWstress(0.0, 0.2, thEquiv);

        // water in soil
        //irrigation either not (rained), or from file, or auto.
        // file/auto should switch on current date?
        double irrigation = field.isAutoIrrigation() ? wd.get(4) : 0.0;

        double precipitation = field.getScaleRain() / 100 * wd.get(_iRain) + irrigation;
       System.out.println("precipitation: "+ precipitation + "\n");
        double ET0reference = wd.get(3);
        double ETrainFactor = (precipitation > 0) ? 1 : 0;
        double kdf = -0.47;
        double ll = Math.exp(kdf * LA / _APPi);
      //  double cropFactor = Math.max(1 - ll * 0.8, ETrainFactor);
        double cropFactor = 1- ll * 0.8;
        double transpiration = cropFactor * ET0reference;
        double swfe = Math.pow(relTheta(thetaL.get(0)), 2.5);
        double actFactor = Math.max(ll * swfe, ETrainFactor);
        double evaporation = actFactor * ET0reference;

        double actualTranspiration = transpiration * WStrans;
        List<Double> wuptrL = multiplyListsWithConstant(krootL, actualTranspiration / Kroot);

        double drain = 0.0;
        List<Double> qFlow = new ArrayList<>(Collections.nCopies(_nsl + 1, 0.0));
        qFlow.set(0, (precipitation - evaporation) / (_lw * 1000.0));

        for (int i = 1; i < qFlow.size(); ++i) {
            double thdown = (i < _nsl)
                    ? thetaL.get(i)
                    : (_zerodrain)
                    ? thetaL.get(i - 1) + _thg
                    : _thm;
            qFlow.set(i, qFlow.get(i) +
                    (thetaL.get(i - 1) + _thg - thdown) * _rateFlow * (thetaL.get(i - 1) / _ths) +
                    4.0 * Math.max(thetaL.get(i - 1) - _ths, 0));
        }

        List<Double> dThetaDt = new ArrayList<>();
        for (int i = 0; i < _nsl; ++i) {
            double dTheta = qFlow.get(i) - qFlow.get(i + 1) - wuptrL.get(i) / (_lw * 1000.0);
            dThetaDt.add(dTheta);
            if (Double.isNaN(dTheta)) {
                System.out.println("dThetaDt: " + dTheta + " qFlow: " + qFlow);
            }
        }

        drain = qFlow.get(_nsl) * _lw * 1000;
        // nutrient concentrations in the plant
        double Nopt = 45 * LDM + 7 * SRDM + 20 * SDM + 20 * RDM;
        double NuptLimiter = 1.0 - fNSstress(Nupt, 2.0 * Nopt, 3.0 * Nopt);
        List<Double> nuptrL = new ArrayList<>();
        for (int i = 0; i < _nsl; i++) {
            double nuptr = monod(ncontL.get(i) * _BD / (1000 * thetaL.get(i)),
                    NuptLimiter * rlL.get(i) * 0.8,
                    12.0 * 0.5);
            nuptrL.add(nuptr);
            if (Double.isNaN(nuptr)) {
                System.out.println("ncont_l=" + ncontL + " theta_l=" + thetaL);
            }
        }

        List<Double> ncontrL = new ArrayList<>(Collections.nCopies(_nsl, 0.0));
        List<Double> _NminR_l = new ArrayList<>();
        for (int d = 0; d < _nsl; d++) {
            double nminR = field.getFertilizationLevel() / 100.0 *
                    36.0 / (_lvol * _BD) /
                    Math.pow(d + 1, 2);
            _NminR_l.add(nminR);
        }

        for (int i = 0; i < _nsl; i++) {
            ncontrL.set(i, _NminR_l.get(i));
            ncontrL.set(i, ncontrL.get(i) - nuptrL.get(i) / (_BD * _lvol)); //mg/day/ (m3*kg/m3)
            double Nl = ncontL.get(i);
            double Nu = (i > 0) ? ncontL.get(i - 1) : -ncontL.get(i);
            double Nd = (i < (_nsl - 1)) ? ncontL.get(i + 1) : 0.0;
            ncontrL.set(i, ncontrL.get(i) + qFlow.get(i) * (Nu + Nl) / 2.0 - qFlow.get(i + 1) * (Nl + Nd) / 2.0);
        }

        double NSphot = (Nopt > 1e-3) ? fNSstress(Nupt, 0.7 * Nopt, Nopt) : 1.0;
        double NSshoot = (Nopt > 1e-3) ? fNSstress(Nupt, 0.7 * Nopt, 0.9 * Nopt) : 1.0;
        double NSroot = (Nopt > 1e-3) ? fNSstress(Nupt, 0.5 * Nopt, 0.7 * Nopt) : 1.0;
        double NSleafSenescence = (Nopt > 1.0) ? 1.0 - fNSstress(Nupt, 0.8 * Nopt, Nopt) : 0.0;

        // sink strength
        double mGRl = logistic(ct, 0.3, 70, 0, 0.9);
        double mGRld = logistic(ct, 0.0, 70.0 + _leafAge, 0.1, -0.90);
        double mGRs = logistic(ct, 0.2, 95, 0.219, 1.87) +
                logistic(ct, 0.0, 209, 0.219, 1.87 - 0.84);
        double mGRr = 0.02 + (0.2 + Math.exp(-0.8 * ct - 0.2)) * mGRl;
        double mGRsr = Math.min(7.08, Math.pow(Math.max(0.0, (ct - 32.3) * 0.02176), 2));
        double mDMr = 0.02 * ct + 1.25 + 0.25 * ct -
                1.25 * Math.exp(-0.8 * ct) * mGRl +
                (0.25 + Math.exp(-0.8 * ct)) * mDMl;

        double CSphot = getStress(Clab, TDM, 0.05, -9999.9, true);
        double CSshoota = getStress(Clab, TDM, -0.05, -9999.9, false);
        double CSshootl = lim((5 - LA / _APPi), 0, 1);
        double CSshoot = CSshoota * CSshootl;
        double CSroot = getStress(Clab, TDM, -0.03, -9999.9, false);
        double CSsrootl = getStress(Clab, TDM, -0.0, -9999.9, false);
        double CSsrooth = getStress(Clab, TDM, 0.01, 0.20, false);
        double starchRealloc = getStress(Clab, TDM, -0.2, -0.1, true) * -0.05 * SRDM;
        double CSsroot = CSsrootl + 2 * CSsrooth;
        double SFleaf = WSshoot * NSshoot * TSshoot * CSshootl;
        double SFstem = WSshoot * NSshoot * TSshoot * CSshoot;
        double SFroot = WSroot * NSroot * TSroot * CSroot;
        double SFsroot = CSsroot;

        double CsinkL = cDm * mGRl * SFleaf;
        double CsinkS = cDm * mGRs * SFstem;
        double CsinkR = cDm * mGRr * SFroot;
        double CsinkSR = cDm * mGRsr * SFsroot - starchRealloc;
        double Csink = CsinkL + CsinkS + CsinkR + CsinkSR;

        // biomass partitioning
        double a2l = CsinkL / Math.max(1e-10, Csink);
        double a2s = CsinkS / Math.max(1e-10, Csink);
        double a2r = CsinkR / Math.max(1e-10, Csink);
        double a2sr = CsinkSR / Math.max(1e-10, Csink);

        // carbon to growth
        double CFG = Csink;// carbon needed for growth (g C/day)
        // increase in plant dry Mass (g DM/day) not including labile carbon pool
        double IDM = Csink / cDm;
        double PPFD = wd.get(2);
        double SFphot = Math.min(Math.min(TSphot, WSphot), Math.min(NSphot, CSphot));
        double CFR = photoFixMean(PPFD, LA / _APPi, -0.47, 29.37 * SFphot, 0.05553, 0.90516);
        System.out.println("\n ----Carbon to growth-----");
        System.out.println("CFG = " + CFG);
        System.out.println("IDM = " + IDM);
        System.out.println("PPFD = " + PPFD);
        System.out.println("SFphot = " + SFphot);
        System.out.println("CFR = " + CFR);

        //photosynthesis
        double SDMR = a2s * IDM;
        double SRDMR = a2sr * IDM;
        double SLA = fSLA(ct);
        double LDRstress = WSleafSenescence * NSleafSenescence * LDM * -1.0;
        double LDRage = mGRld * ((mDMl > 0) ? LDM / mDMl : 1.0);
        if (LDRstress > 1e-10 || LDRage > 1e-10) {
            throw new AssertionError("LDRstress: " + LDRstress + " LDRage: " + LDRage);
        }
        double LDRm = Math.max(-LDM, LDRstress + LDRage);
        double LDRa = Math.max(-LA, fSLA(Math.max(0.0, ct - _leafAge)) * LDRm);
        double LAeR = SLA * a2l * IDM + LDRa;// Leaf Area expansion Rate (m2/day)
        double LDMR = a2l * IDM + LDRm;// leaf growth rate (g/day) - death rate (g/day)

        double RDMR = a2r * IDM; // fine root growth rate (g/day)
        double RLR = _SRL * RDMR;
        List<Double> rlrL = new ArrayList<>();
        for (int i = 0; i < _nsl; ++i) {
            double ln1 = RLR * nrtL.get(i) / NRT;
            rlrL.add(ln1);
        }
        double ln0 = 0.0;
        List<Double> nrtrL = new ArrayList<>();
        for (int i = 0; i < _nsl; ++i) {
            double ln1 = rlrL.get(i);
            nrtrL.add(ln1 * 60.0 + Math.max(0, (ln0 - ln1 - 6.0 * _lw) * 10.0 / _lw));
            ln0 = ln1;
        }

        double mRR = 0.003 * RDM + 0.0002 * SRDM + 0.003 * LDM + 0.0002 * SDM;
        double gRR = 1.8 * RDMR + 0.2 * SRDMR + 1.8 * (LDMR - LDRm) + 0.4 * SDMR;
        double RR = mRR + gRR;

        double ClabR = (CFR - CFG - RR) / cDm;
        cnt = -1;
        List<Double> YR = new ArrayList<>();
        YR.add(++cnt, LDMR);
        YR.add(++cnt, SDMR);
        YR.add(++cnt, RDMR);
        YR.add(++cnt, SRDMR);
        YR.add(++cnt, LAeR);
        YR.add(++cnt, mGRl);
        YR.add(++cnt, mGRs);
        YR.add(++cnt, (double) mGRsr); // Using (double) to convert to double
        YR.add(++cnt, ClabR);
        YR.addAll(rlrL);
        YR.addAll(nrtrL);
        YR.addAll(dThetaDt);
        YR.addAll(ncontrL);
        YR.addAll(nuptrL);

        YR.add((double) irrigation); // Just for reporting amount of water needed
        YR.add(wd.get(0)); // rain
        YR.add((double) actualTranspiration); // Just for reporting amount of water needed
        YR.add(evaporation);
        YR.add(drain);
        YR.add(CFR);
        YR.add(PPFD);

        return YR;
    }

     public double fWstress(double minv, double maxv, double the) {
        double s = 1 / (maxv - minv);
        double i = -1 * minv * s;
        return lim((i + s * relTheta(the)), 0, 1);
    }


    public double fNSstress(double upt, double low, double high) {
        double rr = (upt - low) / (high - low);
        return lim(rr, 0, 1);
    }

    public List<Double> ode2initValues() {
        List<Double> yi = new ArrayList<>();
        // Dữ liệu mới
        Double[] dataToAdd = {
                59.9936, 60.6656, 18.267223323508567, 93.0756,
                2.4665560462821263, 91.79400000000325, 202.84134540781776, 277.46639346799145,
                31.685022446633873, 246.0908445789579, 206.014020579492, 150.50254098663453,
                84.81732279105968, 24.99698068055664, 14771.450674737114, 12376.546166518712,
                9053.470870979236, 5109.872440430796, 1509.2600113651777, 0.2381801339197489,
                0.23832060394348634, 0.2528704670241301, 0.27385732934211704, 0.2953060649096794,
                0.5699703070593461, 0.45975876345694205, 3.0590914791309287, 1.0999841454865997,
                6.4442547370882055, 7888.635585812703, 6728.453739220788, 5256.491465187306,
                2797.305390900457, 1209.871412709246, 0.0, 258.30194219999834,
                440.58641444762077, 197.81995900125494, 55.96304865702387, 590.9555448821034,
                44512.398073766424
        };

        for (Double value : dataToAdd) {
            yi.add(value);
        }
        return yi;
    }

    void intStep(final List<Double> y, final List<Double> r, final double dt) {
        assert (y.size() == r.size());
        for (int i = 0; i < y.size(); ++i) {
            y.set(i, y.get(i) + dt * r.get(i));
        }
    }
    private List<Double> add(List<Double> a, List<Double> b) {
        List<Double> r = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            r.add(a.get(i) + b.get(i));
        }
        return r;
    }

    private List<Double> mul(List<Double> a, double k) {
        List<Double> r = new ArrayList<>();
        for (double v : a) {
            r.add(v * k);
        }
        return r;
    }
    private void saveStep(List<List<Double>> results, List<Double> y, List<Double> wd, double t) {

        while (results.size() < 10) {
            results.add(new ArrayList<>());
        }

        results.get(0).add(y.get(0));     // yield (LDM)
        results.get(2).add(wd.get(4));    // irrigation
        results.get(3).add(y.get(4));     // LA
        results.get(4).add(y.get(8));     // labile carbon
        results.get(8).add(t);            // time
    }

    public double calculateCurrentThEquiv(List<Double> w) {
        // Dựa trên cấu trúc chỉ số trong hàm ode2
        // rlL bắt đầu từ index 9
        // thetaL bắt đầu từ index 9 + 2*_nsl (tức là 19)

        int rlL_startIndex = 9;
        int thetaL_startIndex = 9 + 2 * _nsl;

        List<Double> rlL = w.subList(rlL_startIndex, rlL_startIndex + _nsl);
        List<Double> thetaL = w.subList(thetaL_startIndex, thetaL_startIndex + _nsl);

        // Logic tính toán giống hệt trong ode2
        List<Double> krootL = new ArrayList<>();
        for (int i = 0; i < _nsl; ++i) {
            krootL.add(fKroot(thetaL.get(i), rlL.get(i)));
        }

        double Kroot = krootL.stream().mapToDouble(Double::doubleValue).sum();
        Kroot = Math.max(1e-8, Kroot);

        double thEquiv;
        if (Kroot > 1e-8) {
            double sumThetaKroot = 0.0;
            for (int i = 0; i < _nsl; ++i) {
                sumThetaKroot += thetaL.get(i) * krootL.get(i);
            }
            thEquiv = sumThetaKroot / Kroot;
        } else {
            thEquiv = thetaL.get(0);
        }

        return thEquiv;
    }

    public void calculateFcthresholdAndIrrigationRate(Field field) {

        // =========================
        // Threshold (soil moisture)
        // =========================
        _fcthresold = field.getFieldCapacity();
        _fcthresold *= (_ths - _thr) / 100.0;
        _fcthresold += _thr;

        // =========================
        // Irrigation duration (hour → day)
        // =========================
        double irrigationDurationDays = field.getIrrigationDuration() / 24.0;

        // =========================
        // Drip system
        // =========================
        double dhr = field.getDripRate();               // l/hour
        double dhd = field.getDistanceBetweenHole();    // cm
        double dld = field.getDistanceBetweenRow();     // cm

        // =========================
        // Irrigation rate (mm/day)
        // =========================
        _IrrigationRate = dhr * 24.0 / (dhd * dld / 10000.0);
    }

    private double calculateIrrigation(Field field, List<Double> w, List<Double> wd, double t) {

        double currentTh = calculateCurrentThEquiv(w);

        double currentHour = (t % 1.0) * 24.0;

        boolean isMorning = (currentHour >= 6 && currentHour < 7);
        boolean isAfternoon = (currentHour >= 16 && currentHour < 17);

        boolean hasIrrigated = (t - _autoIrrigateTime) < 0.5;

        if (!hasIrrigated && (isMorning || isAfternoon) && currentTh < _fcthresold) {
            _autoIrrigateTime = t;
        }

        double duration = field.getIrrigationDuration() / 24.0;

        if (t < _autoIrrigateTime + duration) {
            return _IrrigationRate;
        }

        return 0;
    }
}
 */
@Service
public class FieldSimulator {

    @Autowired
    private SensorValueService sensorValueService; // Service lấy dữ liệu combined cậu vừa tạo

    public Map<String, Object> runSimulation(String fieldId) throws IOException {
        // 1. Lấy dữ liệu từ MongoDB (Định dạng chuỗi CSV: time,time,rad,temp,rain,hum,wind)
       List<String> combinedData = sensorValueService.getCombinedValues(fieldId);

        if (combinedData == null || combinedData.isEmpty()) {
            throw new RuntimeException("Không có dữ liệu cảm biến cho cánh đồng này");
        }

        // 2. Khởi tạo đối tượng Field (Model của Kiên)
        Field field = new Field("field simulation");

        // 3. Nạp dữ liệu vào Model (Hàm mình đã viết ở câu trước)
        field.loadAllWeatherDataFromMongo(combinedData);
        //field.loadAllWeatherDataFromCsvFile();
        // 4. Chạy mô phỏng
        field.runModel();

        // 5. Trả về kết quả (Giả sử cậu muốn lấy thông tin sau khi chạy)
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "Mô phỏng hoàn tất cho field simulation ");
        result.put("dataPoints", combinedData.size());
        // Cậu có thể lấy thêm các giá trị kết quả từ đối tượng field ở đây

        return result;
    }
}