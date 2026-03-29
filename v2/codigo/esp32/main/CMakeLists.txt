idf_component_register(SRCS "main.c"
                        INCLUDE_DIRS "."
                        PRIV_REQUIRES driver esp_wifi esp_event nvs_flash mqtt spiffs)

target_compile_options(${COMPONENT_TARGET} PRIVATE "-Wno-error")