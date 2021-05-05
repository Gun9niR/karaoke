//
// Created by 66328 on 2021/4/29.
//

#ifndef HELLO_LIBS_SETTING_H
#define HELLO_LIBS_SETTING_H
#include <cstring>

#include <string>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include <vector>
#include <fstream>
#include <sstream>
#include <iostream>
#include <algorithm>
using namespace std;

const char dataDir[] = "/data/data/com.sjtu.karaoke/raterdata/";
const float correctnessInterval = 0.25;
const int correctnessThreshold = 70;
const float OnceRightScore = 1.3;
const float OnceNotCorrectScore = 0.4;
const float OnceWrongScore = 0.8;
const float f0Shift = 0.1;

#endif //HELLO_LIBS_SETTING_H
