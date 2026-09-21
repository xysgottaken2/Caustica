"""Surface diagnostic output in check annotations when artifact/log storage is inaccessible."""
from pathlib import Path
text = Path('build-output.log').read_text(errors='replace')[-20000:]
text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print('::error title=Gradle diagnostic::' + text)
