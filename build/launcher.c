/*
 * Исполняемый python для APK. В официальном пакете CPython для Android есть только libpython,
 * поэтому «python» — это эта программа: обычная точка входа интерпретатора.
 *
 * Файл кладётся в нативные библиотеки приложения как libfrxpython.so: Android 10+ разрешает приложению
 * запускать (exec) только файлы из папки нативных библиотек APK. Поэтому FRX работает как на ПК —
 * sys.executable указывает сюда, и keeper, main.py, `python -m pip` запускаются обычными подпроцессами.
 */
#include <Python.h>

int main(int argc, char **argv) {
    return Py_BytesMain(argc, argv);
}
