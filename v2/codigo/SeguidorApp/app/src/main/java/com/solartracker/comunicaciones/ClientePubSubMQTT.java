package com.solartracker.comunicaciones;

import android.app.Activity;
import android.util.Log;

import com.solartracker.datos.AlmacenDatosRAM;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * Cliente MQTT robusto v2.2.
 * Utiliza MqttClient estándar (Java) en lugar de MqttAndroidClient para evitar 
 * restricciones de Android 14 (SecurityException en AlarmPingSender).
 */
public class ClientePubSubMQTT implements MqttCallback {

    private MqttClient client;
    private String topicSubFast;
    private String topicSubSlow;
    private String topicPub;
    
    // Cola para desacoplar recepción de procesamiento
    private java.util.concurrent.ConcurrentLinkedQueue<String> colaMensajes = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static final String TAG = "ClientePubSubMQTT";

    public ClientePubSubMQTT(Activity actividad) {
        this.topicSubFast = AlmacenDatosRAM.topicSubFast;
        this.topicSubSlow = AlmacenDatosRAM.topicSubSlow;
        this.topicPub = AlmacenDatosRAM.topicPub;
    }

    public void conectar() {
        // La conexión de MqttClient estándar es síncrona, debe correr en un hilo aparte
        new Thread(() -> {
            try {
                String clientId = MqttClient.generateClientId();
                Log.d(TAG, "=> conectando vía MqttClient estándar a: " + AlmacenDatosRAM.MQTTHOST);
                
                client = new MqttClient(AlmacenDatosRAM.MQTTHOST, clientId, new MemoryPersistence());
                client.setCallback(this);
                
                MqttConnectOptions options = new MqttConnectOptions();
                options.setUserName(AlmacenDatosRAM.USERNAME);
                options.setPassword(AlmacenDatosRAM.PASSWORD.toCharArray());
                options.setCleanSession(true);
                options.setAutomaticReconnect(true);
                options.setConnectionTimeout(30);
                options.setKeepAliveInterval(60);
                
                // RESTAURADO: Last Will para avisar desconexión de app
                options.setWill(AlmacenDatosRAM.topicAppStatus, "offline".getBytes(), 1, true);

                client.connect(options);
                
                // Suscripciones tras conectar con éxito (restaurando QoS donde aplica)
                client.subscribe(topicSubFast, 0); // Tráfico alto -> QoS 0
                client.subscribe(topicSubSlow, 1); // Tráfico bajo -> QoS 1
                client.subscribe(AlmacenDatosRAM.topicSubRecord, 1);
                client.subscribe(AlmacenDatosRAM.topicSubDone, 1);
                client.subscribe(AlmacenDatosRAM.topicEspStatus, 1);
                client.subscribe(AlmacenDatosRAM.topicPubAck, 1);

                // Publicar presencia online
                publicarPresencia("online");

                AlmacenDatosRAM.conectado_PubSub = "Monitoreo activado";
                AlmacenDatosRAM.conectado = true;
                Log.d(TAG, "=> Conectado satisfactoriamente");

            } catch (Exception e) {
                Log.e(TAG, "Error crítico en conexión MQTT", e);
                AlmacenDatosRAM.conectado_PubSub = "Falla: " + e.getMessage();
                AlmacenDatosRAM.conectado = false;
            }
        }).start();
    }

    @Override
    public void connectionLost(Throwable cause) {
        Log.e(TAG, "Conexión perdida", cause);
        AlmacenDatosRAM.conectado = false;
        AlmacenDatosRAM.conectado_PubSub = "Conexión perdida";
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            String payload = new String(message.getPayload());
            colaMensajes.add("TOPIC:" + topic + "|" + payload);
            if (colaMensajes.size() > 100) colaMensajes.poll();
        } catch (Exception e) {
            Log.e(TAG, "Error en messageArrived", e);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    public String leerString() {
        return colaMensajes.poll();
    }

    public void publicarPresencia(String status) {
        publicar(AlmacenDatosRAM.topicAppStatus, status);
    }

    public void publicar(String payload) {
        publicar(topicPub, payload);
    }

    public void publicar(String topic, String payload) {
        if (client != null && client.isConnected()) {
            try {
                MqttMessage message = new MqttMessage(payload.getBytes());
                message.setQos(0);
                client.publish(topic, message);
            } catch (Exception e) {
                Log.e(TAG, "Error al publicar", e);
            }
        }
    }

    public void desconectar() {
        new Thread(() -> {
            try {
                if (client != null && client.isConnected()) {
                    client.disconnect();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error al desconectar", e);
            } finally {
                AlmacenDatosRAM.conectado = false;
                AlmacenDatosRAM.conectado_PubSub = "DESCONECTADO";
            }
        }).start();
    }
}
