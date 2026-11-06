.text
.p2align 2
.global easylsp_demo_intercept_value
.type easylsp_demo_intercept_value, %function
easylsp_demo_intercept_value:
    hint #34
    add x0, x0, #1
.global easylsp_demo_intercept_point
easylsp_demo_intercept_point:
    add x0, x0, #2
    ret
.size easylsp_demo_intercept_value, .-easylsp_demo_intercept_value
.p2align 2
.global easylsp_demo_float_value
.type easylsp_demo_float_value, %function
easylsp_demo_float_value:
    hint #34
    fmov d1, #1.0
    fadd d0, d0, d1
.global easylsp_demo_float_point
easylsp_demo_float_point:
    fadd d0, d0, d1
    ret
.size easylsp_demo_float_value, .-easylsp_demo_float_value
.section .note.GNU-stack,"",%progbits
