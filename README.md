### Running / Testing the application
You can use the `jar` gradle task to create an executable jar of the application
```shell
./gradlew jar 
```
You can then run it using
```shell
java -jar ./build/libs/ssh-example-1.0-SNAPSHOT.jar <PATH_TO_SOCKET>
```
where PATH_TO_SOCKET is the path at which you want your socket file to be created.<br>
If you run the application without any arguments a help will print:
```text
Usage: ssh-example <socket-path> <file-path>
Example: ssh-example /tmp/ssh-example ~/example.txt
Note: Running the program will delete the socket file, if it exists.
```
To then test the program you can run 
```shell
./repl.sh <PATH_TO_SOCKET_FILE>
```
