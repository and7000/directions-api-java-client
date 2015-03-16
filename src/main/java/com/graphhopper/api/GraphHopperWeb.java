/*
 *  Licensed to GraphHopper and Peter Karich under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  GraphHopper licenses this file to you under the Apache License, 
 *  Version 2.0 (the "License"); you may not use this file except in 
 *  compliance with the License. You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.api;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopperAPI;
import com.graphhopper.util.FinishInstruction;
import com.graphhopper.util.Instruction;
import com.graphhopper.util.InstructionAnnotation;
import com.graphhopper.util.InstructionList;
import com.graphhopper.util.PointList;
import com.graphhopper.util.RoundaboutInstruction;
import com.graphhopper.util.StopWatch;
import com.graphhopper.util.ViaInstruction;
import com.graphhopper.util.shapes.GHPoint;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main wrapper of the GraphHopper Directions API for a simple and efficient
 * usage.
 * <p/>
 * @author Peter Karich
 */
public class GraphHopperWeb implements GraphHopperAPI {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private String serviceUrl = "https://graphhopper.com/api/1/route";
    private OkHttpClient downloader = new OkHttpClient();
    private boolean instructions = true;
    private boolean elevation = false;
    private String key = "";

    public GraphHopperWeb() {
    }

    public void setDownloader(OkHttpClient downloader) {
        this.downloader = downloader;
    }

    public OkHttpClient getDownloader() {
        return downloader;
    }

    @Override
    public boolean load(String serviceUrl) {
        this.serviceUrl = serviceUrl;
        return true;
    }

    public GraphHopperWeb setInstructions(boolean b) {
        instructions = b;
        return this;
    }

    public GraphHopperWeb setElevation(boolean withElevation) {
        this.elevation = withElevation;
        return this;
    }

    public GraphHopperWeb setKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalStateException("Key cannot be empty");
        }
        this.key = key;
        return this;
    }

    @Override
    public GHResponse route(GHRequest request) {
        StopWatch sw = new StopWatch().start();
        double took = 0;
        try {
            String places = "";
            for (GHPoint p : request.getPoints()) {
                places += "point=" + p.lat + "," + p.lon + "&";
            }

            boolean tmpInstructions = request.getHints().getBool("instructions", instructions);
            boolean tmpElevation = request.getHints().getBool("elevation", elevation);
            String tmpKey = request.getHints().get("key", key);

            String url = serviceUrl
                    + "?"
                    + places
                    + "&type=json"
                    + "&instructions=" + tmpInstructions
                    + "&points_encoded=true"
                    + "&algo=" + request.getAlgorithm()
                    + "&locale=" + request.getLocale().toString()
                    + "&elevation=" + tmpElevation;

            if (!request.getVehicle().isEmpty()) {
                url += "&vehicle=" + request.getVehicle();
            }

            if (!tmpKey.isEmpty()) {
                url += "&key=" + tmpKey;
            }

            Request okRequest = new Request.Builder().url(url).build();
            String str = downloader.newCall(okRequest).execute().body().string();
            JSONObject json = new JSONObject(str);
            GHResponse res = new GHResponse();

            if (json.getJSONObject("info").has("errors")) {
                JSONArray errors = json.getJSONObject("info").getJSONArray("errors");

                for (int i = 0; i < errors.length(); i++) {
                    JSONObject error = errors.getJSONObject(i);
                    String exClass = error.getString("details");
                    String exMessage = error.getString("message");

                    if (exClass.equals(UnsupportedOperationException.class.getName())) {
                        res.addError(new UnsupportedOperationException(exMessage));
                    } else if (exClass.equals(IllegalStateException.class.getName())) {
                        res.addError(new IllegalStateException(exMessage));
                    } else if (exClass.equals(RuntimeException.class.getName())) {
                        res.addError(new RuntimeException(exMessage));
                    } else if (exClass.equals(IllegalArgumentException.class.getName())) {
                        res.addError(new IllegalArgumentException(exMessage));
                    } else {
                        res.addError(new Exception(exClass + " " + exMessage));
                    }
                }

                return res;

            } else {
                took = json.getJSONObject("info").getDouble("took");
                JSONArray paths = json.getJSONArray("paths");
                JSONObject firstPath = paths.getJSONObject(0);
                double distance = firstPath.getDouble("distance");
                int time = firstPath.getInt("time");
                String pointStr = firstPath.getString("points");
                PointList pointList = WebHelper.decodePolyline(pointStr, 100, tmpElevation);

                if (tmpInstructions) {
                    JSONArray instrArr = firstPath.getJSONArray("instructions");

                    InstructionList il = new InstructionList(null);
                    int viaCount = 1;
                    for (int instrIndex = 0; instrIndex < instrArr.length(); instrIndex++) {
                        JSONObject jsonObj = instrArr.getJSONObject(instrIndex);
                        double instDist = jsonObj.getDouble("distance");
                        String text = jsonObj.getString("text");
                        long instTime = jsonObj.getLong("time");
                        int sign = jsonObj.getInt("sign");
                        JSONArray iv = jsonObj.getJSONArray("interval");
                        int from = iv.getInt(0);
                        int to = iv.getInt(1);
                        PointList instPL = new PointList(to - from, tmpElevation);
                        for (int j = from; j <= to; j++) {
                            instPL.add(pointList, j);
                        }

                        InstructionAnnotation ia = InstructionAnnotation.EMPTY;
                        if (jsonObj.has("annotation_importance") && jsonObj.has("annotation_text")) {
                            ia = new InstructionAnnotation(jsonObj.getInt("annotation_importance"), jsonObj.getString("annotation_text"));
                        }

                        Instruction instr;
                        if (sign == Instruction.USE_ROUNDABOUT || sign == Instruction.LEAVE_ROUNDABOUT) {
                            instr = new RoundaboutInstruction(sign, text, ia, instPL);
                        } else if (sign == Instruction.REACHED_VIA) {
                            ViaInstruction tmpInstr = new ViaInstruction(text, ia, instPL);
                            tmpInstr.setViaCount(viaCount);
                            viaCount++;
                            instr = tmpInstr;
                        } else if (sign == Instruction.FINISH) {
                            instr = new FinishInstruction(instPL, 0);
                        } else {
                            instr = new Instruction(sign, text, ia, instPL);
                        }

                        // The translation is done from the routing service so just use the provided string
                        // instead of creating a combination with sign and name etc
                        instr.setUseRawName();

                        instr.setDistance(instDist).setTime(instTime);
                        il.add(instr);
                    }
                    res.setInstructions(il);
                }
                return res.setPoints(pointList).setDistance(distance).setMillis(time);
            }
        } catch (Exception ex) {
            throw new RuntimeException("Problem while fetching path " + request.getPoints() + ": " + ex.getMessage(), ex);
        } finally {
            logger.debug("Full request took:" + sw.stop().getSeconds() + ", API took:" + took);
        }
    }
}
