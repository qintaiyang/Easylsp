#define ANDROID_LOG_INFO 4
static inline int __android_log_write(int priority, const char *tag, const char *text) {
    (void)priority; (void)tag; (void)text; return 0;
}
