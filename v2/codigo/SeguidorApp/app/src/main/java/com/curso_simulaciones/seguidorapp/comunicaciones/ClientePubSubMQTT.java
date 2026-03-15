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
        // Requerimiento: Last Will "offline"
        options.setWill(AlmacenDatosRAM.topicAppStatus, "offline".getBytes(), 1, true);

        try {
            client.connect(options, null, this);
            AlmacenDatosRAM.conectado_PubSub = "Conectando a " + AlmacenDatosRAM.MQTTHOST + "...";
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

            // Requerimiento: Notificar presencia "online" con retain=true
            publicarOnline();

            AlmacenDatosRAM.conectado_PubSub = "Monitoreo activado";
            AlmacenDatosRAM.conectado = true;
        } catch (Exception e) {
            Log.e(TAG, "Error al suscribir", e);
            AlmacenDatosRAM.conectado_PubSub = "Error Suscripción: " + e.getMessage();
        }
    }

    @Override
    public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
        String errorMsg = "Falla de conexión: " + (exception != null ? exception.getMessage() : "Desconocido");
        Log.e(TAG, errorMsg, exception);
        AlmacenDatosRAM.conectado_PubSub = errorMsg;
        AlmacenDatosRAM.conectado = false;
    }

    @Override
    public void connectionLost(Throwable throwable) {
        String errorMsg = "Conexión perdida: " + (throwable != null ? throwable.getMessage() : "Broker desconectado");
        Log.e(TAG, errorMsg, throwable);
        AlmacenDatosRAM.conectado = false;
        AlmacenDatosRAM.conectado_PubSub = errorMsg;
    }

    @Override
    public synchronized void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        String payload = new String(mqttMessage.getPayload());
        Log.d(TAG, "Mensaje de " + topic + ": " + payload);
        // Aceptamos cualquier dato de nuestros tópicos de suscripción
        if (topic.equals(topicSubFast) || topic.equals(topicSubSlow) || 
            topic.equals(AlmacenDatosRAM.topicSubRecord) || topic.equals(AlmacenDatosRAM.topicSubDone)) {
            
            // Si es telemetría normal, agregar a la cola
            if (topic.equals(topicSubFast) || topic.equals(topicSubSlow)) {
                colaMensajes.add(payload);
                if (colaMensajes.size() > 50) colaMensajes.poll();
            }
            
            // Procesamiento inmediato de descarga (hilo separado)
            if (topic.equals(AlmacenDatosRAM.topicSubRecord) || topic.equals(AlmacenDatosRAM.topicSubDone)) {
                 // Notificar a la UI o procesador
                 // Agregamos a la cola para que Actividad lo procese en su hilo
                 colaMensajes.add("TOPIC:" + topic + "|" + payload);
            }
        }
    }

    private void publicarOnline() {
        if (client != null && client.isConnected()) {
            try {
                MqttMessage message = new MqttMessage("online".getBytes());
                message.setQos(1);
                message.setRetained(true);
                client.publish(AlmacenDatosRAM.topicAppStatus, message);
            } catch (Exception e) {
                Log.e(TAG, "Error al publicar online", e);
            }
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    public String leerString() {
        return colaMensajes.poll();
    }

    public void publicar(String payload) {
        publicar(null, payload);
    }

    public void publicar(String topic, String payload) {
        if (AlmacenDatosRAM.conectado && client != null) {
            try {
                String targetTopic = (topic == null) ? topicPub : topic;
                MqttMessage message = new MqttMessage();
                message.setPayload(payload.getBytes());
                message.setQos(0);
                client.publish(targetTopic, message);
            } catch (Exception e) {
                Log.e(TAG, "Error al publicar en " + topic, e);
            }
        }
    }

    public void desconectar() {
        try {
            if (client != null) {
                client.disconnect();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error al desconectar", e);
        }
    }
}
