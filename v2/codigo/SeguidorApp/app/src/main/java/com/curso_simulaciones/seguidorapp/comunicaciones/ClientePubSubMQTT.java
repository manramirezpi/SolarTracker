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
    private String topicPub;
    private String datoString;

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
        client = new MqttAndroidClient(actividad.getApplicationContext(), AlmacenDatosRAM.MQTTHOST, clientId, Ack.AUTO_ACK);
        client.setCallback(this);
        
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(AlmacenDatosRAM.USERNAME);
        options.setPassword(AlmacenDatosRAM.PASSWORD.toCharArray());
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);

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
            AlmacenDatosRAM.conectado_PubSub = "Monitoreo activado (FAST/SLOW)";
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
        if (topic.equals(topicSubFast) || topic.equals(topicSubSlow)) {
            datoString = payload;
        }
    }


    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    public synchronized String leerString() {
        String data = datoString;
        datoString = null; // Limpiar para evitar reprocesar el mismo dato
        return data;
    }

    public void publicar(String payload) {
        if (AlmacenDatosRAM.conectado && client != null) {
            try {
                MqttMessage message = new MqttMessage();
                message.setPayload(payload.getBytes());
                message.setQos(0);
                client.publish(topicPub, message);
            } catch (Exception e) {
                Log.e(TAG, "Error al publicar", e);
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
