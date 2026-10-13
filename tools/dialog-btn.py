import re
# координаты кнопки системного диалога (ОК/Подождать/…), если есть
try:
    x = open(r'C:\Projects\239\dist\uid.xml', encoding='utf-8', errors='replace').read()
except OSError:
    raise SystemExit
m = re.search(r'text="(?:ОК|OK|Wait|Подождать|Закрыть|Отмена|Повторить)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
if m:
    print((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)
