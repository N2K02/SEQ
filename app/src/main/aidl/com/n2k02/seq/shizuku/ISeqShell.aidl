package com.n2k02.seq.shizuku;
interface ISeqShell {
  int uid() = 1;
  String audioSnapshot() = 2;
  void destroy() = 16777114;
}
