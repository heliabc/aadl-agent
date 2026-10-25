技术路线：llama.cpp支持的gbnf约束解码

在语法正确性上，该方法确实可以有效约束大模型的生成以确保语法结构上aadl代码的正确性，但是存在以下几个问题：

1.  静态语义无法保障：

    例如：system simple

    end sssssimple;

    这种生成是被允许的因为，simple和sssssimple在gbnf中都属于identifier，所以无法做出准确的语义保障。其余包括引用未声明的组件，进一步的语义如连接和绑定都无法进行约束。

2.  无法约束生成个数

    例如：system simple

    end simple;

    system simple

    end simple;

    system simple

    end simple;

    这种连续生成是被允许的，这是由于gbnf语法天生无法对个数进行约束，属于bnf自身缺陷。

3.  gbnf规则的书写

    由于gbnf是llama.cpp自定义的，因此ebnf转向gbnf时可能会出现规则错误，除此之外生成多余的空行等问题也不容忽视。另外llama.cpp对gbnf的支持程度也有待测试，规则太多、嵌套太深、可选分支太多时会直接段错误。

    后续实验：

    提示词工程+gbnf约束解码+静态检测补救

    用提示词工程提升生成质量（如，要生成哪些组件，组件个数等等），生成后，可基于规则对一些错误进行检测，如未定义组件、端口连接错误等。
