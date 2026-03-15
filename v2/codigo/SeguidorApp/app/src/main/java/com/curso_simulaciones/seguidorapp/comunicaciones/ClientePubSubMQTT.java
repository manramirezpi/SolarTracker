package com.curso_simulaciones.seguidorapp.comunicaciones;

import android.app.Activity;
import android.util.Log;

import com.curso_simulaciones.seguidorapp.datos.AlmacenDatosRAM;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import info.mqtt.android.service.Ack;
import info.mqtt.android.service.MqttAndroidClient;

public class ClientePubSubMQTT implements MqttCallback, IMqttActionListener {

    private Activity actividad;
    private MqttAndroidClient client;
    private String topicSubFast;
    private String topicSubSlow;
    private String topicSubBatch;
    private String topicPub;

    private java.util.concurrent.ConcurrentLinkedQueue<String> colaMensajes = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static final String TAG = "ClientePubSubMQTT";

    public ClientePubSubMQTT(Activity actividad) {
        this.actividad = actividad;
        this.topicSubFast = AlmacenDatosRAM.topicSubFast;
        this.topicSubSlow = AlmacenDatosRAM.topicSubSlow;
        this.topicPub = AlmacenDatosRAM.topicPub;
    }

    public void conectar() {
        String clientId = MqttClient.generateClientId();
        Log.d(TAG, "Intentando conectar a: " + AlmacenDatosRAM.MQTTHOST + " con ID: " + clientId);
        client = new MqttAndroidClient(actividad.getApplicationContext(), AlmacenDatosRAM.MQTTHOST, clientId,
                Ack.AUTO_ACK);
        client.setCallback(this);

        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(AlmacenDatosRAM.USERNAME);
        options.setPassword(AlmacenDatosRAM.PASSWORD.toCharArray());
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        // Configuration of Last Will: Payload "offline" to topic solar/app/status
        options.setWill(AlmacenDatosRAM.topicAppStatus, "offline".getBytes(), 1, true);

        try {
            client.connect(options, null, this);
            AlmacenDatosRAM.conectado_PubSub = "CONECTANDO...";
        } catch (Exception e) {
            Log.e(TAG, "Error crítico al llamar connect()", e);
            AlmacenDatosRAM.conectado_PubSub = "Error: " + e.getMessage();
        }
    }

    @Override
    public void onSuccess(IMqttToken asyncActionToken) {
        try {
            Log.d(TAG, "Conexión exitosa. Suscribiendo a canales de telemetría...");
            client.subscribe(topicSubFast, 0);
            client.subscribe(topicSubSlow, 0);
            client.subscribe(AlmacenDatosRAM.topicSubRecord, 1);
            client.subscribe(AlmacenDatosRAM.topicSubDone, 1);
            client.subscribe(AlmacenDatosRAM.topicEspStatus, 1);
            client.subscribe(AlmacenDatosRAM.topicPubAck, 1);

            // Publicar presencia "online"
            publicarPresencia("online");

            AlmacenDatosRAM.conectado_PubSub = "Monitoreo activado";
            AlmacenDatosRAM.conectado = true;
        } catch (Exception e) {
            Log.e(TAG, "Error al suscribir", e);
            AlmacenDatosRAM.conectado_PubSub = "Error Suscripción: " + e.getMessage();
        }
    }

    private void publicarPresencia(String status) {
        if (client != null && client.isConnected()) {
            try {
                MqttMessage message = new MqttMessage(status.getBytes());
                message.setQos(1);
                message.setRetained(true);
                client.publish(AlmacenDatosRAM.topicAppStatus, message);
            } catch (Exception e) {
                Log.e(TAG, "Error al publicar presencia: " + status, e);
            }
        }
    }

    @Override
    public void connectionLost(Throwable throwable) {
        Log.w(TAG, "Conexión perdida. Paho intentará reconexión automática.");
        AlmacenDatosRAM.conectado = false;
        AlmacenDatosRAM.conectado_PubSub = "RECONECTANDO...";
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        if (reconnect) {
            Log.i(TAG, "Reconexión automática exitosa a " + serverURI);
            publicarPresencia("online");
            AlmacenDatosRAM.conectado = true;
            AlmacenDatosRAM.conectado_PubSub = "Monitoreo activado";
        }
    }

    @Override
    public synchronized void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        String payload = new String(mqttMessage.getPayload());
        if (topic.equals(topicSubFast) || topic.equals(topicSubSlow) || 
            topic.equals(AlmacenDatosRAM.topicSubRecord) || topic.equals(AlmacenDatosRAM.topicSubDone) ||
            topic.equals(AlmacenDatosRAM.topicEspStatus)) {
            
            colaMensajes.add("TOPIC:" + topic + "|" + payload);
            if (colaMensajes.size() > 100) colaMensajes.poll();
        }
    }

    public void publicar(String payload) {
        publicar(topicPub, payload);
    }

    public void publicar(String topic, String payload) {
        if (client != null && client.isConnected()) {
            try {
                MqttMessage message = new MqttMessage(payload.getBytes());
                message.setQos(1);
                client.publish(topic, message);
            } catch (Exception e) {
                Log.e(TAG, "Error al publicar: " + payload, e);
            }
        }
    }

    public String leerString() {
        return colaMensajes.poll();
    }

    public void desconectar() {
        try {
            if (client != null && client.isConnected()) {
                publicarPresencia("offline");
                // Breve espera antes de cerrar el socket para asegurar que el mensaje sale
                Thread.sleep(200);
                client.disconnect();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error al desconectar", e);
        }
    }
}
