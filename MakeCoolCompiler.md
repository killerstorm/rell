#RELL Compiler (Easier) procedure with IntelliJ

* Pull the repo.

> Don’t have IntelliJ? 
> 
> Get the Jar file somehow and put it in a folder
>
> If you don’t know how to get the jar ask it to a friendly Chromaway employee.

##Create the artifact JAR with IntelliJ 
* Open project structure ( `cmd ;` )
* Go to Artifacts 
* Add one. (`+`)
* Jar -> from module with dependencies
* Select main class `HelloKt`
* Set as output directory the root of the project (rellr) 
* Ok and close
* __If for some reason you manifest is under src/main/java/META_INF/ move it to src/main/resources/META_INF/__ (https://stackoverflow.com/questions/20952713/wrong-manifest-mf-in-intellij-idea-created-jar/21074091#21074091)

## Make the jar
* From IntelliJ menu
* Build > Build Artifacts > yourArtifact > build
* Jar file should be generated on root folder

## Add script to .bashrc (or .zshrc)
Now create a script called `rell` in your script folder (or any folder where you can put script and can export from .bashrc) (https://unix.stackexchange.com/questions/129143/what-is-the-purpose-of-bashrc-and-how-does-it-work)

This is the script
~~~
#!/bin/sh

set -e

scriptdir=`dirname "$BASH_SOURCE"`
outputname="$PWD/$1".sql
if [ $1 = "-h" ]
  then
    echo "compiler"
    echo "args[0] = input file (Rell file to compile)"
    echo "args[1] = OPTIONAL field, name of the file in output (SQL file) default is input name"
    exit 1
fi



if [ ! -z "$2" ]
  then
    outputname="$PWD/$2"
fi

eval java -jar "$scriptdir"/rellr/rellr.jar "$PWD/$1" "$outputname"

echo ""
echo ""

echo "Chromaway - RELL"

echo ""

echo Output file"$outputname"
~~~
* Create a symlink to the jar folder (in this case the project folder)

`ln -s ../Documents/ChromaWay/rellr ./`

Now you should be able to call it from everywhere

~~~
rell contractExample.rell [myContract.sql]
~~~

