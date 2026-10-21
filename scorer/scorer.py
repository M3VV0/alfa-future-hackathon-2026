class Scorer:
    scoring_model = None
    
    def __init__(self):
        # здесь будет создаваться и обучаться модель
        pass
    
    # интерфейс класса
    def predict(self, X):
        return self.scoring_model.predict(X) # type: ignore