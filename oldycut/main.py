if __name__=='__main__':
    import sys
    if len(sys.argv)>2 and sys.argv[1]=='--self-test':
        from oldycut.selftest import run
        raise SystemExit(run(sys.argv[2]))
    from oldycut.ui import run
    raise SystemExit(run())
