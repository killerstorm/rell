docker run --net=host --mount type=bind,source="$(pwd)",target="/rell" --rm --tty rell-pytests
