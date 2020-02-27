@echo off
IF NOT DEFINED RELL_JAVA SET RELL_JAVA=java
%RELL_JAVA% -cp "%~dp0lib\*" net.postchain.rell.tools.runcfg.RellRunConfigGenKt %*
