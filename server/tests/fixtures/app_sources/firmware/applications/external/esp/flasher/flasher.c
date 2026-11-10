void flash(void) {
    furi_hal_serial_control_acquire(FuriHalSerialIdUsart);
    storage_file_open(file, path, FSAM_WRITE, FSOM_CREATE_ALWAYS);
}
