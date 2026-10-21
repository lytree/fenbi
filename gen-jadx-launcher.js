const fs = require('fs');
const path = require('path');
const root = 'E:/tools/jadx-1.5.6';
const files = fs.readdirSync(path.join(root, 'lib')).filter(f => f.endsWith('.jar'));

const unixCp = files.map(f => '$DIR/lib/' + f).join(':');
const winCp = files.map(f => '%LIBDIR%\\' + f).join(';');

const bash = [
  '#!/usr/bin/env bash',
  'DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"',
  'exec java -Xmx4g -Dfile.encoding=UTF-8 -cp "' + unixCp + '" jadx.cli.JadxCLI "$@"',
  ''
].join('\n');

const bat = [
  '@echo off',
  'setlocal',
  'for %%i in ("%~dp0..") do set "ROOT=%%~fi"',
  'set "LIBDIR=%ROOT%\\lib"',
  'java -Xmx4g -Dfile.encoding=UTF-8 -cp "' + winCp + '" jadx.cli.JadxCLI %*',
  ''
].join('\r\n');

fs.writeFileSync(path.join(root, 'bin/jadx'), bash);
fs.writeFileSync(path.join(root, 'bin/jadx.bat'), bat);
console.log('jars on classpath:', files.length);
